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

package com.tencent.devops.utils.common

import com.tencent.devops.api.CredentialApi
import com.tencent.devops.pojo.exception.CodeccUserConfigException
import com.tencent.devops.pojo.ticket.CredentialType
import org.slf4j.LoggerFactory
import java.util.Base64
import kotlin.collections.ArrayList

/**
 * This util is to get the credential from core
 * It use DH encrypt and decrypt
 */
object CredentialUtils {

    private val sdkApi = CredentialApi()

    fun getCredentialWithType(credentialId: String): Pair<List<String>, CredentialType> {
        if (credentialId.trim().isEmpty()) {
            throw CodeccUserConfigException("The credential Id is empty")
        }
        val pair = DHUtil.initKey()
        val encoder = Base64.getEncoder()
        logger.info("Start to get the credential($credentialId)")

        val result = sdkApi.get(credentialId, encoder.encodeToString(pair.publicKey))

        if (result.isNotOk() || result.data == null) {
            logger.error("Fail to get the credential($credentialId) because of ${result.message}")
            throw CodeccUserConfigException(result.message!!)
        }

        val credential = result.data!!
        logger.info("Get the credential success")
        val list = ArrayList<String>()

        list.add(
            decode(
                credential.v1,
                credential.publicKey,
                pair.privateKey
            )
        )
        if (!credential.v2.isNullOrEmpty()) {
            list.add(
                decode(
                    credential.v2!!,
                    credential.publicKey,
                    pair.privateKey
                )
            )
        }
        if (!credential.v3.isNullOrEmpty()) {
            list.add(
                decode(
                    credential.v3!!,
                    credential.publicKey,
                    pair.privateKey
                )
            )
        }
        if (!credential.v4.isNullOrEmpty()) {
            list.add(
                decode(
                    credential.v4!!,
                    credential.publicKey,
                    pair.privateKey
                )
            )
        }
        logger.info("Get the credential list success")
        return Pair(list, credential.credentialType)
    }

    private fun decode(encode: String, publicKey: String, privateKey: ByteArray): String {
        val decoder = Base64.getDecoder()
        return String(
            DHUtil.decrypt(
                decoder.decode(encode),
                decoder.decode(publicKey),
                privateKey
            )
        )
    }

    private val logger = LoggerFactory.getLogger(CredentialUtils::class.java)
}
