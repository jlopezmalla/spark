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

import java.io._
import java.nio.file.{Files, Paths}
import java.nio.file.attribute.PosixFilePermissions
import java.security._
import java.security.KeyFactory
import java.security.cert.CertificateFactory
import java.security.spec.{PKCS8EncodedKeySpec, RSAPrivateCrtKeySpec}
import javax.xml.bind.DatatypeConverter

import org.apache.commons.ssl.PKCS8Key
import sun.security.util.DerInputStream

import org.apache.spark.internal.Logging



object SSLConfig extends Logging {

  val sslTypeDataStore = "DATASTORE"

  private val sparkSSLPrefix = "spark.ssl."

  def prepareEnvironment(sslType: String,
                         options: Map[String, String]): Map[String, String] = {


    val trustStoreOptions = generateTruststoreOptions(sslType)
    val keyStoreOptions = generateKeystoreOptions(sslType, options)

    val vaultKeyPassPath = options(s"${sslType}_VAULT_KEY_PASS_PATH")

    val keyPass =
      VaultHelper.getCertPassForAppFromVault(vaultKeyPassPath).get

    val keyPassOptions = Map(
      s"$sparkSSLPrefix${sslType.toLowerCase}.keyPassword" -> keyPass
    )

    val certFilesPath =
      Map(s"$sparkSSLPrefix${sslType.toLowerCase}.certPem.path" ->
        s"${ConfigSecurity.secretsFolder}/cert.crt",
        s"$sparkSSLPrefix${sslType.toLowerCase}.keyPKCS8.path" ->
          s"${ConfigSecurity.secretsFolder}/key.pkcs8",
        s"$sparkSSLPrefix${sslType.toLowerCase}.caPem.path" ->
          s"${ConfigSecurity.secretsFolder}/ca.crt")

    trustStoreOptions ++ keyStoreOptions ++ keyPassOptions ++ certFilesPath
  }

  private def generateTruststoreOptions(sslType: String): Map[String, String] = {

    val getTrustStoreAndPass = for {

       trustStore <- VaultHelper.getAllCas
       trustPass <- VaultHelper.getCAPass

    } yield {

      generatePemFile(trustStore, "ca.crt")

      (generateTrustStore(sslType, trustStore, trustPass), trustPass)
    }

    val (trustStorePath, trustPass) = getTrustStoreAndPass.get

    logInfo(s"Setting SSL values for $sslType")


      Map(s"$sparkSSLPrefix${sslType.toLowerCase}.enabled" -> "true",
        s"$sparkSSLPrefix${sslType.toLowerCase}.trustStore" -> trustStorePath,
        s"$sparkSSLPrefix${sslType.toLowerCase}.trustStorePassword" -> trustPass,
        s"$sparkSSLPrefix${sslType.toLowerCase}.security.protocol" -> "SSL")

  }

  private def generateKeystoreOptions(
                                       sslType: String,
                                       options: Map[String, String]
                                     ): Map[String, String] = {

    val keystoreOptions = for {

      vaultKeystorePath <- options.get(s"${sslType}_VAULT_CERT_PATH")
      vaultKeystorePassPath <- options.get(s"${sslType}_VAULT_CERT_PASS_PATH")

    } yield {

      val certName = options.get(s"${sslType}_CERTIFICATE_NAME")

      val (key, certs) =
        VaultHelper.getCertKeyForAppFromVault(vaultKeystorePath, certName).get

      pemToDer(key)
      generatePemFile(certs, "cert.crt")

      val pass =
        VaultHelper.getCertPassForAppFromVault( vaultKeystorePassPath).get

      val keyStorePath = generateKeyStore(sslType, certs, key, pass)

      Map(s"$sparkSSLPrefix${sslType.toLowerCase}.keyStore" -> keyStorePath,
        s"$sparkSSLPrefix${sslType.toLowerCase}.keyStorePassword" -> pass,
        s"$sparkSSLPrefix${sslType.toLowerCase}.protocol" -> "TLSv1.2",
        s"$sparkSSLPrefix${sslType.toLowerCase}.needClientAuth" -> "true"
      )
    }

    keystoreOptions.getOrElse {
        logInfo(s"trying to get ssl secrets from vault for ${sslType.toLowerCase} keyStore" +
          s" but not found pass and cert vault paths, exiting")
        Map.empty
    }

  }

  def generateTrustStore(sslType: String, cas: String, password: String): String = {

    val keystore = KeyStore.getInstance("JKS")
    keystore.load(null)

    val certs = getBase64FromCAs(cas)

    certs.zipWithIndex.foreach { case (cert, index) =>
      val key = s"cert-${index}"
      keystore.setCertificateEntry(key, generateCertificateFromDER(cert))
    }

    val fileName = "trustStore.jks"
    val dir = new File(s"${ConfigSecurity.secretsFolder}/$sslType")
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

  def generatePemFile(pem: String, fileName: String): Unit = {
      formatPem(pem)
      val bosCA = new BufferedOutputStream(
        new FileOutputStream(s"${ConfigSecurity.secretsFolder}/$fileName"))
      bosCA.write(formatPem(pem).getBytes)
      bosCA.close()
    }

    // Gets raw pem from vault (without \n and folding) and outputs a well-formatted pem

    def formatPem(pemRaw: String): String = {
      val (begin, end) = extractFlagsFromCert(pemRaw)
      val pem = getArrayFromCert(pemRaw)
      pem.map( data => s"$begin\n${data.sliding(64, 64).mkString("\n")}\n$end")
        .mkString("\n")
        .concat("\n")
    }

    def pemToDer(data: String): Unit = {
      val (begin, end) = ("-----BEGIN RSA PRIVATE KEY-----", "-----END RSA PRIVATE KEY-----")
      require(data.startsWith(begin), "BEGIN RSA PRIVATE KEY flag not found")
      val tokens = data.split(begin)(1).split(end)
      val keyByted = DatatypeConverter.parseBase64Binary(tokens(0))
      val pkcs8 = new PKCS8Key(keyByted, null)
      val decrypted = pkcs8.getDecryptedBytes
      val spec = new PKCS8EncodedKeySpec(decrypted)
      val pk = KeyFactory.getInstance("RSA").generatePrivate(spec)
      val bos = new BufferedOutputStream(
        new FileOutputStream(s"${ConfigSecurity.secretsFolder}/key.pkcs8"))
      bos.write(pk.getEncoded)
      bos.close()
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
    val dir = new File(s"${ConfigSecurity.secretsFolder}/$sslType")
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

  private def getArrayFromCert(cert: String): Array[String] = {
    val (begin, end) = extractFlagsFromCert(cert)
    cert.split(begin).tail.map(_.split(end).head)
  }
  private def extractFlagsFromCert(cert: String): (String, String) = {
    val splittedBy = cert.takeWhile(_ == '-')
    val begin = s"$splittedBy${cert.split(splittedBy).tail.head}$splittedBy"
    val end = begin.replace("BEGIN", "END")
    (begin, end)
  }

  private def getBase64FromCAs(cas: String): Array[Array[Byte]] = {
    val pattern = getArrayFromCert(cas)
    pattern.map(value => {
      DatatypeConverter.parseBase64Binary(value)
    })
  }
}
