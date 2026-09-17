package org.las2mile.scrcpy.adb;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Date;

public class AdbKeyManager {
    private static final String TAG = "AdbKeyManager";
    private static final String PRIV_KEY_FILE = "priv.key";
    private static final String CERT_FILE = "cert.der";
    private static final String PUB_KEY_FILE = "pub.key";

    public static class AdbKeyPair {
        private final PrivateKey privateKey;
        private final X509Certificate certificate;

        public AdbKeyPair(PrivateKey privateKey, X509Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }

        public PrivateKey getPrivateKey() {
            return privateKey;
        }

        public X509Certificate getCertificate() {
            return certificate;
        }
    }

    private static AdbKeyPair cachedKeyPair = null;

    public static synchronized AdbKeyPair getKeyPair(Context context) throws Exception {
        if (cachedKeyPair != null) {
            return cachedKeyPair;
        }

        File privFile = context.getFileStreamPath(PRIV_KEY_FILE);
        File certFile = context.getFileStreamPath(CERT_FILE);

        PrivateKey privateKey = null;
        X509Certificate cert = null;

        if (privFile.exists() && privFile.length() > 0) {
            try {
                byte[] privBytes = readFile(privFile);
                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(privBytes);
                KeyFactory kf = KeyFactory.getInstance("RSA");
                privateKey = kf.generatePrivate(spec);
            } catch (Exception e) {
                Log.e(TAG, "Failed to load private key, regenerating", e);
                privateKey = null;
            }
        }

        if (privateKey != null && certFile.exists() && certFile.length() > 0) {
            try {
                byte[] certBytes = readFile(certFile);
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                cert = (X509Certificate) cf.generateCertificate(new FileInputStream(certFile));
            } catch (Exception e) {
                Log.e(TAG, "Failed to load certificate, recreating cert", e);
                cert = null;
            }
        }

        if (privateKey != null && cert == null) {
            // Private key exists but certificate is missing: reconstruct public key and sign cert
            try {
                RSAPrivateCrtKey privCrt = (RSAPrivateCrtKey) privateKey;
                RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(privCrt.getModulus(), privCrt.getPublicExponent());
                KeyFactory kf = KeyFactory.getInstance("RSA");
                PublicKey pubKey = kf.generatePublic(pubSpec);
                cert = generateCertificate(privateKey, pubKey);
                writeFile(certFile, cert.getEncoded());
            } catch (Exception e) {
                Log.e(TAG, "Failed to regenerate cert from existing private key", e);
                privateKey = null;
            }
        }

        if (privateKey == null || cert == null) {
            // Generate a fresh RSA 2048 keypair and certificate
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            java.security.KeyPair kp = kpg.generateKeyPair();
            privateKey = kp.getPrivate();
            cert = generateCertificate(privateKey, kp.getPublic());

            writeFile(privFile, privateKey.getEncoded());
            writeFile(certFile, cert.getEncoded());

            // Also write pub.key for backward compatibility with legacy adblib
            File pubFile = context.getFileStreamPath(PUB_KEY_FILE);
            String pubBase64 = Base64.encodeToString(kp.getPublic().getEncoded(), Base64.NO_WRAP);
            writeFile(pubFile, (pubBase64 + " scrcpy-android\0").getBytes(StandardCharsets.UTF_8));
        }

        cachedKeyPair = new AdbKeyPair(privateKey, cert);
        return cachedKeyPair;
    }

    private static X509Certificate generateCertificate(PrivateKey privKey, PublicKey pubKey) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 86400000L);
        Date notAfter = new Date(now + 100L * 365L * 86400000L); // valid for 100 years
        BigInteger serial = BigInteger.valueOf(now);
        X500Name subject = new X500Name("CN=scrcpy-android");

        SubjectPublicKeyInfo subPubKeyInfo = SubjectPublicKeyInfo.getInstance(pubKey.getEncoded());
        X509v3CertificateBuilder certBuilder = new X509v3CertificateBuilder(
                subject,
                serial,
                notBefore,
                notAfter,
                subject,
                subPubKeyInfo
        );

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(privKey);
        X509CertificateHolder holder = certBuilder.build(signer);
        return new JcaX509CertificateConverter().getCertificate(holder);
    }

    private static byte[] readFile(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = fis.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return data;
    }

    private static void writeFile(File file, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
            fos.flush();
        }
    }
}
