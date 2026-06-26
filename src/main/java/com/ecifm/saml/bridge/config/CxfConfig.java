package com.ecifm.saml.bridge.config;

import org.apache.cxf.Bus;
import org.apache.cxf.BusFactory;
import org.apache.cxf.bus.extension.ExtensionManagerBus;
import org.apache.cxf.transport.ConduitInitiatorManager;
import org.apache.cxf.transport.http.HTTPTransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

@Configuration
public class CxfConfig {

    private static final Logger log = LoggerFactory.getLogger(CxfConfig.class);

    @PostConstruct
    public void init() {
        Bus bus = BusFactory.getDefaultBus(true);
        if (bus instanceof ExtensionManagerBus extBus) {
            ConduitInitiatorManager conduitReg = extBus.getExtension(ConduitInitiatorManager.class);
            if (conduitReg != null) {
                try {
                    HTTPTransportFactory factory = new HTTPTransportFactory();
                    conduitReg.registerConduitInitiator("http://schemas.xmlsoap.org/soap/http", factory);
                    conduitReg.registerConduitInitiator("http://www.w3.org/2003/05/soap/bindings/HTTP/", factory);
                    conduitReg.registerConduitInitiator("http://schemas.xmlsoap.org/wsdl/http/", factory);
                    log.info("Registered HTTPTransportFactory for all SOAP transports");
                } catch (Exception e) {
                    log.warn("Failed to register HTTPTransportFactory: {}", e.getMessage());
                }
            }
        }
    }
}
