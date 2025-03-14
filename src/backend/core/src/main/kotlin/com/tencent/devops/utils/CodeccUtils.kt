/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.utils

import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.bk.devops.atom.AtomContext
import com.tencent.bk.devops.atom.api.SdkEnv
import com.tencent.bk.devops.plugin.docker.PcgDevCloudExecutor
import com.tencent.bk.devops.plugin.utils.JsonUtil
import com.tencent.devops.api.CodeccSdkApi
import com.tencent.devops.docker.Build
import com.tencent.devops.docker.pojo.CommandParam
import com.tencent.devops.docker.pojo.LandunParam
import com.tencent.devops.docker.pojo.ToolConstants
import com.tencent.devops.docker.tools.FileUtil
import com.tencent.devops.docker.tools.LogUtils
import com.tencent.devops.docker.utils.CodeccConfig
import com.tencent.devops.pojo.CodeccCheckAtomParamV3
import com.tencent.devops.pojo.CodeccExecuteConfig
import com.tencent.devops.pojo.CoverityProjectType
import com.tencent.devops.pojo.LinuxCodeccConstants
import com.tencent.devops.pojo.LinuxCodeccConstants.Companion.SVN_PASSWORD
import com.tencent.devops.pojo.OSType
import com.tencent.devops.pojo.OpenScanConfigParam
import com.tencent.devops.pojo.codeccHost
import com.tencent.devops.pojo.exception.CodeccDependentException
import com.tencent.devops.pojo.exception.CodeccUserConfigException
import com.tencent.devops.pojo.imageRegistryPwdKey
import com.tencent.devops.utils.CodeccParamsHelper.addCommonParams
import com.tencent.devops.utils.CodeccParamsHelper.getCovToolPath
import com.tencent.devops.utils.CodeccParamsHelper.getGoMetaLinterPath
import com.tencent.devops.utils.CodeccParamsHelper.getGoRootPath
import com.tencent.devops.utils.CodeccParamsHelper.getJdkPath
import com.tencent.devops.utils.CodeccParamsHelper.getKlocToolPath
import com.tencent.devops.utils.CodeccParamsHelper.getNodePath
import com.tencent.devops.utils.CodeccParamsHelper.getPyLint2Path
import com.tencent.devops.utils.CodeccParamsHelper.getPyLint3Path
import com.tencent.devops.utils.CodeccParamsHelper.getPython2Path
import com.tencent.devops.utils.CodeccParamsHelper.getPython3Path
import com.tencent.devops.utils.common.AgentEnv
import com.tencent.devops.utils.common.AtomUtils
import com.tencent.devops.utils.script.BatScriptUtil
import com.tencent.devops.utils.script.ScriptUtils
import com.tencent.devops.utils.script.ShellUtil
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

open class CodeccUtils {

    protected var codeccStartFile: String = ""

    fun executeCommand(codeccExecuteConfig: CodeccExecuteConfig): String {
        val codeccWorkspace = CodeccEnvHelper.getCodeccWorkspace(codeccExecuteConfig.atomContext.param)
        try {
            initData(codeccExecuteConfig, codeccWorkspace)
            return doRun(codeccExecuteConfig, codeccWorkspace)
        } finally {
            if (codeccWorkspace.exists() && !LogUtils.getDebug()) codeccWorkspace.delete()
        }
    }

    private fun doRun(codeccExecuteConfig: CodeccExecuteConfig, codeccWorkspace: File): String {
//        println("devops.slave.environment: ${System.getenv("devops.slave.environment") }")
//        println("DEVOPS_SLAVE_ENVIRONMENT: ${System.getenv("DEVOPS_SLAVE_ENVIRONMENT")}")

        if ("pcg-devcloud" == System.getenv("DEVOPS_SLAVE_ENVIRONMENT") && System.getProperty("user.name") != "root") {
            throw CodeccUserConfigException("检查到当前是pcg公共资源，启动用户是${System.getProperty("user.name")}，请在编辑流水线页面选中机器的“使用root用户执行”选项")
        }

        return if (CodeccSdkUtils.runWithOldPython(codeccWorkspace)) {
            println("[初始化] Run CodeCC scan with python script...")
            doOldCodeccSingleCommand(codeccExecuteConfig, codeccWorkspace)
        } else {
            println("[初始化] Run CodeCC scan with docker...")
            doCodeccSingleCommand(codeccExecuteConfig, codeccWorkspace)
        }
    }

    private fun initData(config: CodeccExecuteConfig, codeccWorkspace: File) {
//        coverityStartFile = CodeccParamsHelper.getCovPyFile(config.scriptType, codeccWorkspace)
//        toolsStartFile = CodeccParamsHelper.getToolPyFile(config.scriptType, codeccWorkspace)
        codeccStartFile = CodeccScriptUtils().downloadScriptFile(config, codeccWorkspace).canonicalPath
        println("CodeCC start file($codeccStartFile)")
    }

    open fun doPreCodeccSingleCommand(command: MutableList<String>, codeccExecuteConfig: CodeccExecuteConfig) {
        val pythonCmd = exportPython3(command, codeccExecuteConfig)
        command.add("export LANG=zh_CN.UTF-8\n")
        command.add("export PATH=/data/bkdevops/apps/codecc/go/bin:/data/bkdevops/apps/codecc/gometalinter/bin:\$PATH\n")
        command.add("$pythonCmd -V\n")
        command.add("pwd\n")
    }

    // py -3在安装python3时，没装到用户路径下，会报找不到默认python的错误
    private fun exportPython3(command: MutableList<String>, codeccExecuteConfig: CodeccExecuteConfig): String {
        val workspace = File(codeccExecuteConfig.atomContext.param.bkWorkspace)
        try {
            ScriptUtils.execute(
                script = "py -3 -V",
                runtimeVariables = codeccExecuteConfig.variable,
                dir = workspace,
                printErrorLog = false
            )
            return "py -3"
        } catch (e: Exception) {
            System.err.println(e.message)
        }

        try {
            ScriptUtils.execute(
                script = "python3 -V",
                runtimeVariables = codeccExecuteConfig.variable,
                dir = workspace,
                printErrorLog = false
            )
            return "python3"
        } catch (e: Exception) {
            System.err.println(e.message)
        }
        val constants = LinuxCodeccConstants(codeccExecuteConfig.atomContext.param.bkWorkspace)
        command.add("export PATH=${getPython3Path(constants)}:\$PATH\n")
        return "python"
    }

    fun doCodeccSingleCommand(
        codeccExecuteConfig: CodeccExecuteConfig,
        codeccWorkspace: File
    ): String {
        val param = codeccExecuteConfig.atomContext.param
        val constants = LinuxCodeccConstants(codeccExecuteConfig.atomContext.param.bkWorkspace)

        val map = mutableMapOf<String, String>()

        val workspace = File(codeccExecuteConfig.atomContext.param.bkWorkspace)
        val script = param.script ?: ""

        val scriptFile = getScriptFile(
            codeccExecuteConfig,
            script = script,
            workspace = workspace
        )

        val scanTools = if (codeccExecuteConfig.filterTools.isNotEmpty()) {
            codeccExecuteConfig.filterTools
        } else {
            codeccExecuteConfig.tools
        }
        if (scanTools.isEmpty()) return "scan tools is empty"

        // 添加公共参数
        addCommonParams(map, codeccExecuteConfig)

        // 添加具体业务参数
        map["SCAN_TOOLS"] = scanTools.joinToString(",").toLowerCase()
        map["OFFLINE"] = "true"
        map["DATA_ROOT_PATH"] = codeccWorkspace.canonicalPath
        map["STREAM_CODE_PATH"] = workspace.canonicalPath
        map["PY27_PATH"] = getPython2Path(constants)
        map["PY35_PATH"] = getPython3Path(constants)

        if (scanTools.contains("PYLINT")) {
            map["PY27_PYLINT_PATH"] = getPyLint2Path(constants)
            map["PY35_PYLINT_PATH"] = getPyLint3Path(constants)
        } else {
            // 两个参数是必填的
            // 把路径配置成其他可用路径就可以
            map["PY27_PYLINT_PATH"] = workspace.canonicalPath
            map["PY35_PYLINT_PATH"] = workspace.canonicalPath
        }
        // map["GOROOT"] = "/data/bkdevops/apps/codecc/go"

        // 之前Coverity参数
        map["IS_SPEC_CONFIG"] = "true"
        map["COVERITY_RESULT_PATH"] = codeccWorkspace.canonicalPath

        val buildCmd = when (CodeccParamsHelper.getProjectType(JsonUtil.getObjectMapper().readValue(param.languages
            ?: "[]"))) {
            CoverityProjectType.UN_COMPILE -> {
                "--no-command --fs-capture-search ."
            }
            CoverityProjectType.COMPILE -> scriptFile.canonicalPath
            CoverityProjectType.COMBINE -> "--fs-capture-search . ${scriptFile.canonicalPath}"
        }
        // 工蜂开源扫描就不做限制
        val coreCount = max(Runtime.getRuntime().availableProcessors() / 2, 1) // 用一半的核

        map["PROJECT_BUILD_COMMAND"] = "--parallel-translate=$coreCount $buildCmd"
        if (!AgentEnv.isThirdParty()) {
            map["COVERITY_HOME_BIN"] = "${getCovToolPath(constants)}/bin"
        }
        map["PROJECT_BUILD_PATH"] = workspace.canonicalPath
        map["SYNC_TYPE"] = (param.asynchronous == false).toString()
        if (!AgentEnv.isThirdParty() && scanTools.contains("KLOCWORK")) {
            map["KLOCWORK_HOME_BIN"] = getKlocToolPath(constants)
        }
        if (!param.goPath.isNullOrBlank()) {
            map["GO_PATH"] = param.goPath!!
        }
        val agentId = SdkEnv.getSdkHeader()["X-DEVOPS-AGENT-ID"] ?: ""
        val agentSecretKey = SdkEnv.getSdkHeader()["X-DEVOPS-AGENT-SECRET-KEY"] ?: ""

        val landunParam = LandunParam(
            userId = param.pipelineUpdateUserName,
            buildId = param.pipelineBuildId,
            devopsProjectId = param.projectName,
            devopsBuildType = AgentEnv.getBuildType(),
            devopsAgentId = agentId,
            devopsAgentSecretKey = agentSecretKey,
            devopsAgentVmSid = SdkEnv.getVmSeqId(),
            devopsPipelineId = param.pipelineId,
            devopsVmSeqId = SdkEnv.getVmSeqId(),
            ldEnvType = CodeccParamsHelper.getEnvType(),
            streamCodePath = workspace.canonicalPath,
            channelCode = codeccExecuteConfig.atomContext.param.channelCode,
            toolNames = scanTools.joinToString(","),
            toolImageTypes = ""
        )


        CodeccConfig.loadToolMeta(
            landunParam,
            codeccHost,
            imageRegistryPwdKey
        )

        //设置coverity当前版本：
        CodeccConfig.setConfig("COVERITY_NEW_VERSION", codeccExecuteConfig.atomContext.getSensitiveConfParam("COVERITY_VERSION") ?: "")

        //灰度是否开启？
        val is_coverity_gray = codeccExecuteConfig.atomContext.getSensitiveConfParam("COVERITY_IS_GRAY")
        if (is_coverity_gray?.toBoolean() == true){
            //设置coverity版本升级灰度
            var grayTaskList = codeccExecuteConfig.atomContext.getSensitiveConfParam("COVERITY_GRAY_VERSION").split("#")?: emptyList()
            grayTaskList.forEach { it
                if (it.contains("=")) {
                    var grayVersion = it.split("=").get(0)
                    var taskList = it.split("=").get(1).split(";") ?: emptyList()
                    if (taskList.contains(codeccExecuteConfig.atomContext.param.codeCCTaskId)) {
                        println("this job in gray list, the coverity version is: " + grayVersion)
                        CodeccConfig.setConfig("COVERITY_NEW_VERSION", grayVersion)
                    }
                }
            }
        }

        val commandParam = CommandParam(
            landunParam = landunParam,
            scmType = CodeccRepoHelper.getScmType(codeccExecuteConfig.repos),
            svnUser = if (codeccExecuteConfig.repos.firstOrNull()?.svnUerPassPair != null) {
                codeccExecuteConfig.repos.firstOrNull()?.svnUerPassPair!!.first
            } else {
                ""
            },
            svnPassword = if (codeccExecuteConfig.repos.firstOrNull()?.svnUerPassPair != null) {
                codeccExecuteConfig.repos.firstOrNull()?.svnUerPassPair!!.second
            } else {
                ""
            },
            scmSshAccess = if (CodeccRepoHelper.getScmType(codeccExecuteConfig.repos) == "svn") {
                CodeccRepoHelper.getScmType(codeccExecuteConfig.repos)
            } else {
                ""
            },
            repoUrlMap = if (CodeccParamsHelper.getRepoUrlMap(codeccExecuteConfig) == "{}") {
                    ""
                }else{
                    CodeccParamsHelper.getRepoUrlMap(codeccExecuteConfig)
                },
            repoRelPathMap = codeccExecuteConfig.repos.map {
                it.repoHashId to it.relPath
            }.toMap(),
            repoScmRelpathMap = CodeccParamsHelper.getRepoScmRelPathMap(codeccExecuteConfig),
            subCodePathList = AtomUtils.transferPathParam(param.path),
            scanTools = scanTools.joinToString(",").toLowerCase(),
            dataRootPath = codeccWorkspace.canonicalPath,
            py27Path = getPython2Path(constants),
            py35Path = getPython3Path(constants),
            py27PyLintPath = if (scanTools.contains("PYLINT")) {
                getPyLint2Path(constants)
            } else {
                workspace.canonicalPath
            },
            py35PyLintPath = if (scanTools.contains("PYLINT")) {
                getPyLint3Path(constants)
            } else {
                workspace.canonicalPath
            },
            subPath = getSubPath(constants),
            goRoot = "/data/bkdevops/apps/codecc/go",
            coverityResultPath = codeccWorkspace.canonicalPath,
            projectBuildCommand = "--parallel-translate=$coreCount $buildCmd",
            coverityHomeBin = "/data/bkdevops/apps/codecc/cov-analysis-linux64-"+CodeccConfig.getConfig("COVERITY_NEW_VERSION")+"/bin",
            projectBuildPath = workspace.canonicalPath,
            syncType = !(param.asynchronous ?: false),
            klockWorkHomeBin = CodeccConfig.getConfig("KLOCWORK_HOME_BIN") ?: getKlocToolPath(constants),
            pinpointHomeBin = CodeccConfig.getConfig("PINPOINT_HOME_BIN") ?: "/data/bkdevops/apps/codecc/pinpoint",
            codeqlHomeBin = CodeccConfig.getConfig("CODEQL_HOME_BIN") ?: "/data/bkdevops/apps/codecc/codeql",
            clangHomeBin = CodeccConfig.getConfig("CLANG_HOME_BIN") ?: "/data/codecc_software/clang_scan/clang-11.0/bin",
            spotBugsHomeBin = workspace.canonicalPath + "/.temp/codecc_scan/codecc_agent/bin/spotbugs/tool/spotbugs-4.0.6/bin",
            goPath = if (!param.goPath.isNullOrBlank()) {
                param.goPath!!
            } else {
                ""
            },
            gatherDefectThreshold = (codeccExecuteConfig.atomContext.getSensitiveConfParam("GATHER_DEFECT_THRESHOLD")
                ?: "10000").toLong(),
            needPrintDefect = needPrintDefect(param.projectName),
            openScanPrj = param.openScanPrj,
            extraPrams = getExtraParams(codeccExecuteConfig.atomContext),
            resharperHomeBin = CodeccConfig.getConfig("RESHARPER_HOME_BIN") ?: "C:\\data\\codecc_software\\resharper_scan"
        )
        LogUtils.printLog("codecc scan command param : $commandParam")

        val openScanConfigParam = OpenScanConfigParam()

        openScanConfigParam.openScanFilterBgId = codeccExecuteConfig.atomContext.getSensitiveConfParam("OPEN_SCAN_FILTER_BGID") ?: ""
        openScanConfigParam.coverityFilterBg = codeccExecuteConfig.atomContext.getSensitiveConfParam("COVERITY_FILTER_BG") ?: "0"
        openScanConfigParam.coverityScanPeriod = try {
            (codeccExecuteConfig.atomContext.getSensitiveConfParam("COVERITY_SCAN_PERIOD") ?: "1").toInt()
        } catch (e : Exception){
            1
        }

        return Build.build(param as CodeccCheckAtomParamV3, codeccExecuteConfig.timeOut, commandParam, openScanConfigParam)
    }

    private fun getExtraParams(atomContext: AtomContext<out CodeccCheckAtomParamV3>): Map<String, String> {
        val resultMap = mutableMapOf<String, String>()
        with(atomContext) {
            if (!param.hookRepoId.isNullOrBlank()) resultMap[CommandParam.extraHookRepoIdKey] = param.hookRepoId!!
            if (!param.hookMrSourceBranch.isNullOrBlank()) resultMap[CommandParam.extraHookMrSourceBranchKey] = param.hookMrSourceBranch!!
            if (!param.hookMrTargetBranch.isNullOrBlank()) resultMap[CommandParam.extraHookMrTargetBranchKey] = param.hookMrTargetBranch!!
            resultMap["BK_CODECC_SCAN_MODE"] = atomContext.allParameters["BK_CODECC_SCAN_MODE"]?.toString() ?: ""
        }

        resultMap["devCloudAppId"] = atomContext.getSensitiveConfParam("devCloudAppId") ?: ""
        resultMap["devCloudUrl"] = atomContext.getSensitiveConfParam("devCloudUrl") ?: ""
        resultMap["devCloudToken"] = atomContext.getSensitiveConfParam("devCloudToken") ?: ""
        resultMap[PcgDevCloudExecutor.PCG_TOKEN_SECRET_ID] = atomContext.getSensitiveConfParam(PcgDevCloudExecutor.PCG_TOKEN_SECRET_ID) ?: ""
        resultMap[PcgDevCloudExecutor.PCG_TOKEN_SECRET_KEY] = atomContext.getSensitiveConfParam(PcgDevCloudExecutor.PCG_TOKEN_SECRET_KEY) ?: ""
        resultMap[PcgDevCloudExecutor.PCG_REQUEST_HOST] = atomContext.getSensitiveConfParam(PcgDevCloudExecutor.PCG_REQUEST_HOST) ?: ""

        return resultMap
    }

    fun needPrintDefect(projectCode: String): Boolean {
        return projectCode.startsWith("_") || projectCode.startsWith("git_")
                || projectCode.startsWith("github_")
    }

    open fun doOldPreCodeccSingleCommand(
        command: MutableList<String>,
        codeccExecuteConfig: CodeccExecuteConfig
    ) {
        val pythonCmd = exportPython3(command, codeccExecuteConfig)
        command.add("export LANG=zh_CN.UTF-8\n")
        command.add("export PATH=~/.pyenv/shims:/data/bkdevops/apps/codecc/go/bin:/data/bkdevops/apps/codecc/gometalinter/bin:\$PATH\n")
        command.add("$pythonCmd -V\n")
        command.add("pwd\n")

        command.add(pythonCmd)
        command.add(codeccStartFile)
    }

    fun doOldCodeccSingleCommand(
        codeccExecuteConfig: CodeccExecuteConfig,
        codeccWorkspace: File
    ): String {
        val param = codeccExecuteConfig.atomContext.param
        val constants = LinuxCodeccConstants(codeccExecuteConfig.atomContext.param.bkWorkspace)

        val command = mutableListOf<String>()
        doOldPreCodeccSingleCommand(command, codeccExecuteConfig)

        val workspace = File(codeccExecuteConfig.atomContext.param.bkWorkspace)
        val script = param.script ?: ""
        val scriptFile = getScriptFile(codeccExecuteConfig, script, workspace)

        var scanTools = if (codeccExecuteConfig.filterTools.isNotEmpty()) {
            codeccExecuteConfig.filterTools
        } else {
            codeccExecuteConfig.tools
        }

        scanTools = scanTools.filter { it.toLowerCase() !in ToolConstants.CODE_TOOLS_ACOUNT }
        CodeccSdkApi.uploadActualExeTools(param, scanTools)
        if (scanTools.isEmpty()) return "scan tools is empty"

        // 添加公共参数
        addCommonParams(command, codeccExecuteConfig)

        CodeccConfig.loadPropertiesForOld()

        //设置coverity当前版本：
        CodeccConfig.setConfig("COVERITY_NEW_VERSION", codeccExecuteConfig.atomContext.getSensitiveConfParam("COVERITY_VERSION"))

        //灰度是否开启？
        val is_coverity_gray = codeccExecuteConfig.atomContext.getSensitiveConfParam("COVERITY_IS_GRAY")
        if (is_coverity_gray?.toBoolean()!!){
            //设置coverity版本升级灰度
            var grayTaskList = codeccExecuteConfig.atomContext.getSensitiveConfParam("COVERITY_GRAY_VERSION").split("#")?: emptyList()
            grayTaskList.forEach { it
                if (it.contains("=")) {
                    var grayVersion = it.split("=").get(0)
                    var taskList = it.split("=").get(1).split(";") ?: emptyList()
                    if (taskList.contains(codeccExecuteConfig.atomContext.param.codeCCTaskId)) {
                        println("this job in gray list, the coverity version is: " + grayVersion)
                        CodeccConfig.setConfig("COVERITY_NEW_VERSION", grayVersion)
                    }
                }
            }
        }

        // 添加具体业务参数
        command.add("-DCOVERITY_NEW_VERSION=${CodeccConfig.getConfig("COVERITY_NEW_VERSION")}")
        command.add("-DSCAN_TOOLS=${scanTools.joinToString(",").toLowerCase()}")
        command.add("-DOFFLINE=true")
        command.add("-DDATA_ROOT_PATH=${codeccWorkspace.canonicalPath}")
        command.add("-DSTREAM_CODE_PATH=${workspace.canonicalPath}")
        command.add("-DPY27_PATH=${getPython2Path(constants)}")
        command.add("-DPY35_PATH=${getPython3Path(constants)}")
        if (scanTools.contains("PYLINT")) {
            command.add("-DPY27_PYLINT_PATH=${getPyLint2Path(constants)}")
            command.add("-DPY35_PYLINT_PATH=${getPyLint3Path(constants)}")
        } else {
            // 两个参数是必填的
            // 把路径配置成其他可用路径就可以
            command.add("-DPY27_PYLINT_PATH=${workspace.canonicalPath}")
            command.add("-DPY35_PYLINT_PATH=${workspace.canonicalPath}")
        }
        command.add("-DSUB_PATH=${getSubPath(constants)}")
        // command.add("-DGOROOT=/data/bkdevops/apps/codecc/go")

        // 之前Coverity参数
        command.add("-DIS_SPEC_CONFIG=true")
        command.add("-DCOVERITY_RESULT_PATH=${codeccWorkspace.canonicalPath}")

        val buildCmd = when (CodeccParamsHelper.getProjectType(JsonUtil.getObjectMapper().readValue(param.languages
            ?: "[]"))) {
            CoverityProjectType.UN_COMPILE -> {
                "--no-command --fs-capture-search ."
            }
            CoverityProjectType.COMPILE -> scriptFile.canonicalPath
            CoverityProjectType.COMBINE -> "--fs-capture-search . ${scriptFile.canonicalPath}"
        }
        // 工蜂开源扫描就不做限制
        val coreCount = max(Runtime.getRuntime().availableProcessors() / 2, 1) // 用一半的核

        command.add("-DPROJECT_BUILD_COMMAND=\"--parallel-translate=$coreCount $buildCmd\"")
        if (!AgentEnv.isThirdParty()) command.add("-DCOVERITY_HOME_BIN=${getCovToolPath(constants)}/bin")
        command.add("-DPROJECT_BUILD_PATH=${workspace.canonicalPath}")
        command.add("-DSYNC_TYPE=${param.asynchronous == false}")
        if (!AgentEnv.isThirdParty() && scanTools.contains("KLOCWORK")) command.add(
            "-DKLOCWORK_HOME_BIN=${getKlocToolPath(constants)}"
        )
        if (!param.goPath.isNullOrBlank()) command.add("-DGO_PATH=${param.goPath}")
        command.add("-DCODECC_API_WEB_SERVER=" + codeccExecuteConfig.atomContext.getSensitiveConfParam("CODECC_API_WEB_SERVER").removePrefix("http://").removeSuffix(":80"))
        command.add("-DNFS_SERVER=" + codeccExecuteConfig.atomContext.getSensitiveConfParam("NFS_SERVER"))
        if (codeccExecuteConfig.repos.isEmpty()) command.add("-DSCM_PASS=true")

        printLog(command, "[codecc] ")

        return ScriptUtils.execute(
            script = command.joinToString(" "),
            dir = workspace,
            runtimeVariables = codeccExecuteConfig.variable,
            prefix = "[codecc] "
        )
    }

    protected open fun getSubPath(constants: LinuxCodeccConstants): String {
        val subPath = if (AgentEnv.isThirdParty()) "" else
            "/usr/local/svn/bin:/data/bkdevops/apps/coverity"
        return "$subPath:${getJdkPath(constants)}:${getNodePath(constants)}:" +
            "${getGoMetaLinterPath(constants)}:${getGoRootPath(constants)}:" +
            "${constants.STYLE_TOOL_PATH}:${constants.PHPCS_TOOL_PATH}:${getGoRootPath(constants)}:${constants.GO_CI_LINT_PATH}"
    }

    private fun printLog(list: List<String>, tag: String) {
        println("$tag command content: ")
        list.forEach {
            if (!it.startsWith("-DSSH_PRIVATE_KEY") &&
                !it.startsWith("-DKEY_PASSWORD") &&
                !it.startsWith("-D$SVN_PASSWORD")
            ) {
                println("$tag $it")
            }
        }
    }

    private fun printLog(map: Map<String, String>, tag: String) {
        println("$tag command content: ")
        map.forEach {
            if (it.key != "SSH_PRIVATE_KEY" && it.key != "KEY_PASSWORD" && it.key != SVN_PASSWORD) {
                println("$tag ${it.value}")
            }
        }
    }

    private fun getScriptFile(codeccExecuteConfig: CodeccExecuteConfig, script: String, workspace: File): File {
        return if (CodeccEnvHelper.getOS() == OSType.WINDOWS) {
            BatScriptUtil.getCommandFile(
                script = script,
                dir = workspace,
                runtimeVariables = codeccExecuteConfig.variable
            )
        } else {
            ShellUtil.getCommandFile(
                script = script,
                dir = workspace,
                buildEnvs = listOf(),
                runtimeVariables = codeccExecuteConfig.variable
            )
        }
    }

    // 因为工具压缩大小超过100MB所以通过官网链接下载，py脚本另行下载
    fun downloadResharper(path: String) {
        val downloadUrl = "https://download.jetbrains.com/resharper/dotUltimate.2022.1.2/JetBrains.ReSharper.CommandLineTools.2022.1.2.zip";
        val dirFile = File(path)
        if (!dirFile.exists()) {
            dirFile.mkdirs()
        } else {
            val pyPath = path + File.separator + "sdk" + File.separator + "src" + File.separator + "scan.py"
            val inspectcodePath = path + File.separator + "tool" + File.separator + "inspectcode.exe"
            if (File(pyPath).exists() && File(inspectcodePath).exists()) {
                LogUtils.printLog("resharper existing, return download")
                return
            }
        }
        val outputPath = path + File.separator + "resharper_scan.zip"
        downloadFile(downloadUrl, outputPath)
        LogUtils.printLog("download resharper rar file success")
        FileUtil.unzipFile(outputPath, "$path${File.separator}tool")
        LogUtils.printLog("unzip resharper success ")
        LogUtils.printLog("start get scan.py")
        getResharperScanPy(path)
        LogUtils.printLog("scan.py get success")
    }

    private fun getResharperScanPy(path: String) {
        val url = "https://raw.githubusercontent.com/TencentBlueKing/ci-codeccScan/master/resharper_scan/sdk/src/scan.py"
        val savePath = path + File.separator + "sdk" + File.separator + "src"
        if (!File(savePath).exists()) {
            File(savePath).mkdirs()
        }
        downloadFile(url, "$savePath${File.separator}scan.py")
    }

    fun downloadFile(downloadUrl: String, saveFilePath: String) {
        val inputStream: InputStream?
        val outputStream: OutputStream?
        try {
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.doInput = true
            conn.doOutput = true
            conn.useCaches = false
            conn.connect()
            inputStream = conn.inputStream
            outputStream = FileOutputStream(saveFilePath)
            val bytes = ByteArray(2048)
            var end = inputStream.read(bytes)
            while (end != -1) {
                outputStream.write(bytes, 0, end)
                end = inputStream.read(bytes)
            }
            inputStream.close()
            outputStream.close()
            conn.disconnect()
        } catch (e: IOException) {
            e.printStackTrace()
            throw CodeccDependentException("download resharper file failure: ${e.message}")
        }
    }
}
