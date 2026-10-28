package com.smartgn.billing.configuration;

import com.smartgn.billing.adapter.*;
import com.smartgn.billing.application.TaxGateway;

import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class TaxBeans {
    @Bean
    TaxGateway taxGateway(TaxConfiguration cfg, Environment env) {
        if (cfg.mode.equals("beta"))
            return new SunatBetaGateway(
                    env.getProperty(
                            "smartgn.tax.endpoint",
                            "https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService"),
                    env.getProperty("smartgn.tax.user", cfg.ruc + "MODDATOS"),
                    env.getProperty("smartgn.tax.password", "MODDATOS"));
        if (cfg.mode.equals("local") && env.matchesProfiles("local")) return new LocalTaxGateway();
        return new TaxGateway() {
            public Receipt submit(String n, byte[] z) {
                throw new IllegalStateException("Tax gateway disabled");
            }

            public Receipt poll(String t) {
                throw new IllegalStateException("Tax gateway disabled");
            }

            public boolean testMode() {
                return true;
            }
        };
    }
}
