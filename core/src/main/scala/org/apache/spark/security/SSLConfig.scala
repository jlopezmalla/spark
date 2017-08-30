/*
* Licensed to the Apache Software Foundation (ASF) under one or more
* contributor license agreements.  See the NOTICE file distributed with
* this work for additional information regarding copyright ownership.
* The ASF licenses this file to You under the Apache License, Version 2.0
* (the "License"); you may not use this file except in compliance with
* the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.spark.security

import java.io.{ByteArrayInputStream, File, FileOutputStream}
import java.nio.file.{Files, Paths}
import java.nio.file.attribute.PosixFilePermissions
import java.security._
import java.security.cert.CertificateFactory
import java.security.spec.RSAPrivateCrtKeySpec
import javax.xml.bind.DatatypeConverter

import sun.security.util.DerInputStream

import org.apache.spark.internal.Logging

object SSLConfig extends Logging {

  val sslTypeDataStore = "DATASTORE"

  def prepareEnvironment(vaultHost: String,
                         vaultToken: String,
                         sslType: String,
                         options: Map[String, String]): Map[String, String] = {

    val sparkSSLPrefix = "spark.ssl."

    val vaultTrustStorePath = options.get(s"${sslType}_VAULT_TRUSTSTORE_PATH")
    val vaultTrustStorePassPath = options.get(s"${sslType}_VAULT_TRUSTSTORE_PASS_PATH")
    val trustStore = VaultHelper.getTrustStore(vaultHost, vaultToken, vaultTrustStorePath.get)
    val trustPass = VaultHelper.getCertPassForAppFromVault(
      vaultHost, vaultTrustStorePassPath.get, vaultToken)
    val trustStorePath = generateTrustStore(sslType, trustStore, trustPass)

    logInfo(s"Setting SSL values for $sslType")

    val trustStoreOptions =
      Map(s"$sparkSSLPrefix${sslType.toLowerCase}.enabled" -> "true",
        s"$sparkSSLPrefix${sslType.toLowerCase}.trustStore" -> trustStorePath,
        s"$sparkSSLPrefix${sslType.toLowerCase}.trustStorePassword" -> trustPass,
        s"$sparkSSLPrefix${sslType.toLowerCase}.security.protocol" -> "SSL")

    val vaultKeystorePath = options.get(s"${sslType}_VAULT_CERT_PATH")

    val vaultKeystorePassPath = options.get(s"${sslType}_VAULT_CERT_PASS_PATH")

    val keyStoreOptions = if (vaultKeystorePath.isDefined && vaultKeystorePassPath.isDefined) {

      val (key, certs) =
        VaultHelper.getCertKeyForAppFromVault(vaultHost, vaultKeystorePath.get, vaultToken)

      val pass = VaultHelper.getCertPassForAppFromVault(
        vaultHost, vaultKeystorePassPath.get, vaultToken)

      val keyStorePath = generateKeyStore(sslType, certs, key, pass)

      Map(s"$sparkSSLPrefix${sslType.toLowerCase}.keyStore" -> keyStorePath,
        s"$sparkSSLPrefix${sslType.toLowerCase}.keyStorePassword" -> pass,
        s"$sparkSSLPrefix${sslType.toLowerCase}.protocol" -> "TLSv1.2",
        s"$sparkSSLPrefix${sslType.toLowerCase}.needClientAuth" -> "true"
      )

    } else {
      logInfo(s"trying to get ssl secrets from vault for ${sslType.toLowerCase} keyStore" +
        s" but not found pass and cert vault paths, exiting")
      Map[String, String]()
    }

    val vaultKeyPassPath = options.get(s"${sslType}_VAULT_KEY_PASS_PATH")

    val keyPass = Map(s"$sparkSSLPrefix${sslType.toLowerCase}.keyPassword"
      -> VaultHelper.getCertPassForAppFromVault(vaultHost, vaultKeyPassPath.get, vaultToken))

    val certFilesPath =
      Map(sparkSSLPrefix + "cert.path" -> s"${sys.env.get("SPARK_SSL_CERT_PATH")}/cert.crt",
        sparkSSLPrefix + "key.pkcs8" -> s"${sys.env.get("SPARK_SSL_CERT_PATH")}/key.pkcs8",
        sparkSSLPrefix + "root.cert" -> s"${sys.env.get("SPARK_SSL_CERT_PATH")}/caroot.crt")

    trustStoreOptions ++ keyStoreOptions ++ keyPass ++ certFilesPath
  }

  private def generateTrustStore(sslType: String, cas: String, password: String): String = {

    val keystore = KeyStore.getInstance("JKS")
    keystore.load(null)
    val certs = getBase64FromCAs(cas)

    certs.zipWithIndex.foreach { case (cert, index) =>
      val key = s"cert-${index}"
      keystore.setCertificateEntry(key, generateCertificateFromDER(cert))
    }

    val fileName = "trustStore.jks"
    val dir = new File(s"/tmp/$sslType")
    dir.mkdirs
    val downloadFile = Files.createFile(Paths.get(dir.getAbsolutePath, fileName),
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    val file = downloadFile.toFile
    file.deleteOnExit
    val writeStream = new FileOutputStream(file)
    keystore.store(writeStream, password.toCharArray)
    writeStream.close
    file.getAbsolutePath
  }


  // TODO Improvent get passwords keys and jks key
  def generateKeyStore(sslType: String,
                       cas: String,
                       firstCA: String,
                       password: String): String = {
    def generatePrivateKeyFromDER(keyCA: String): PrivateKey = {

      val keyBytes = getBase64FromCAs(keyCA).head
      val derReader = new DerInputStream(keyBytes)

      val seq = derReader.getSequence(0)

      if (seq.length < 9) {
        throw new GeneralSecurityException("Could not parse a PKCS1 private key.")
      }

      // skip version seq[0];
      val modulus = seq(1).getBigInteger
      val publicExp = seq(2).getBigInteger
      val privateExp = seq(3).getBigInteger
      val prime1 = seq(4).getBigInteger
      val prime2 = seq(5).getBigInteger
      val exp1 = seq(6).getBigInteger
      val exp2 = seq(7).getBigInteger
      val crtCoef = seq(8).getBigInteger

      val keySpec =
        new RSAPrivateCrtKeySpec(modulus,
          publicExp,
          privateExp,
          prime1,
          prime2,
          exp1,
          exp2,
          crtCoef)

      KeyFactory.getInstance("RSA").generatePrivate(keySpec)
    }

    val keystore = KeyStore.getInstance("JKS")
    keystore.load(null)
    val key = generatePrivateKeyFromDER(firstCA)

    val certs = getBase64FromCAs(cas)
    val arrayCert = certs.map(cert => generateCertificateFromDER(cert))
    val alias = "key-alias"
    keystore.setKeyEntry(alias, key, password.toCharArray, arrayCert)

    val fileName = "keystore.jks"
    val dir = new File(s"/tmp/$sslType")
    dir.mkdirs
    val downloadFile = Files.createFile(Paths.get(dir.getAbsolutePath, fileName),
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    val file = downloadFile.toFile
    file.deleteOnExit
    val writeStream = new FileOutputStream(file)
    keystore.store(writeStream, password.toCharArray)
    writeStream.close
    file.getAbsolutePath
  }

  private def generateCertificateFromDER(certBytes: Array[Byte]): cert.Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certBytes))

  private def getArrayFromCA(ca: String): Array[String] = {
    val splittedBy = ca.takeWhile(_ == '-')
    val begin = s"$splittedBy${ca.split(splittedBy).tail.head}$splittedBy"
    val end = begin.replace("BEGIN", "END")
    ca.split(begin).tail.map(_.split(end).head)
  }

  private def getBase64FromCAs(cas: String): Array[Array[Byte]] = {
    val pattern = getArrayFromCA(cas)
    pattern.map(value => {
      DatatypeConverter.parseBase64Binary(value)
    })
  }
}
