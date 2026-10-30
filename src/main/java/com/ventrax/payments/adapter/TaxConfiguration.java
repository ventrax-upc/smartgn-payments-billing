package com.ventrax.payments.adapter;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;

@Component
public class TaxConfiguration {
    public final String ruc, name, address, ubigeo, keyPath, password, mode;
    public final BigDecimal rate;

    public TaxConfiguration(
            @Value("${smartgn.tax.ruc:}") String ruc,
            @Value("${smartgn.tax.name:}") String name,
            @Value("${smartgn.tax.address:}") String address,
            @Value("${smartgn.tax.ubigeo:150101}") String ubigeo,
            @Value("${smartgn.tax.key-path:}") String keyPath,
            @Value("${smartgn.tax.key-password:}") String password,
            @Value("${smartgn.tax.mode:local}") String mode,
            @Value("${smartgn.tax.rate:0.18}") BigDecimal rate) {
        this.ruc = ruc;
        this.name = name;
        this.address = address;
        this.ubigeo = ubigeo;
        this.keyPath = keyPath;
        this.password = password;
        this.mode = mode;
        this.rate = rate;
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException("Invalid tax rate");
    }

    public boolean configured() {
        return ruc.matches("[0-9]{11}")
                && !name.isBlank()
                && !address.isBlank()
                && !keyPath.isBlank()
                && Files.isRegularFile(Path.of(keyPath));
    }

    public KeyStore.PrivateKeyEntry signingKey() {
        try {
            var ks = KeyStore.getInstance("PKCS12");
            try (var in = Files.newInputStream(Path.of(keyPath))) {
                ks.load(in, password.toCharArray());
            }
            var aliases = ks.aliases();
            while (aliases.hasMoreElements()) {
                String a = aliases.nextElement();
                if (ks.isKeyEntry(a)) {
                    var e =
                            (KeyStore.PrivateKeyEntry)
                                    ks.getEntry(
                                            a,
                                            new KeyStore.PasswordProtection(
                                                    password.toCharArray()));
                    ((X509Certificate) e.getCertificate()).checkValidity();
                    return e;
                }
            }
            throw new IllegalStateException("No signing key");
        } catch (Exception e) {
            throw new IllegalStateException("Fiscal signing configuration unavailable");
        }
    }
}
