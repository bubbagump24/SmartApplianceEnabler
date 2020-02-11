/*
 * Copyright (C) 2017 Axel Müller <axel.mueller@avanux.de>
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package de.avanux.smartapplianceenabler.control;

import de.avanux.smartapplianceenabler.appliance.ApplianceIdConsumer;
import de.avanux.smartapplianceenabler.http.*;
import de.avanux.smartapplianceenabler.appliance.ApplianceLifeCycle;
import de.avanux.smartapplianceenabler.meter.MeterValueName;
import de.avanux.smartapplianceenabler.protocol.ContentProtocolHandler;
import de.avanux.smartapplianceenabler.protocol.ContentProtocolType;
import de.avanux.smartapplianceenabler.protocol.JsonContentProtocolHandler;
import de.avanux.smartapplianceenabler.util.ParentWithChild;
import de.avanux.smartapplianceenabler.util.Validateable;
import org.apache.http.HttpStatus;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.joda.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Changes the on/off state of an appliance by sending an HTTP request.
 *
 * IMPORTANT: The URLs in Appliance.xml have to be escaped (e.g. use "&amp;" instead of "&")
 */
@XmlAccessorType(XmlAccessType.FIELD)
public class HttpSwitch implements Control, ApplianceLifeCycle, Validateable, ApplianceIdConsumer {

    private transient Logger logger = LoggerFactory.getLogger(HttpSwitch.class);
    @XmlElement(name = "HttpConfiguration")
    private HttpConfiguration httpConfiguration;
    @XmlElement(name = "HttpWrite")
    private List<HttpWrite> httpWrites;
    @XmlElement(name = "HttpRead")
    private HttpRead httpRead;
    @XmlAttribute
    private String contentProtocol;
    private transient String applianceId;
    private transient HttpHandler httpHandler = new HttpHandler();
    private transient HttpTransactionExecutor httpTransactionExecutor = new HttpTransactionExecutor();
    private transient ContentProtocolHandler contentContentProtocolHandler;
    protected transient boolean on;
    private transient List<ControlStateChangedListener> controlStateChangedListeners = new ArrayList<>();

    @Override
    public void setApplianceId(String applianceId) {
        this.applianceId = applianceId;
        this.httpTransactionExecutor.setApplianceId(applianceId);
        this.httpHandler.setApplianceId(applianceId);
    }

    public void setHttpConfiguration(HttpConfiguration httpConfiguration) {
        this.httpConfiguration = httpConfiguration;
    }

    public void setHttpTransactionExecutor(HttpTransactionExecutor httpTransactionExecutor) {
        this.httpTransactionExecutor = httpTransactionExecutor;
    }

    public void setHttpWrites(List<HttpWrite> httpWrites) {
        this.httpWrites = httpWrites;
    }

    @Override
    public void init() {
        if(this.httpConfiguration != null) {
            this.httpTransactionExecutor.setConfiguration(this.httpConfiguration);
        }
        this.httpHandler.setHttpTransactionExecutor(httpTransactionExecutor);
    }

    public void validate() {
        HttpValidator validator = new HttpValidator(applianceId);

        List<String> writeValueNames = Arrays.stream(ControlValueName.values())
                .map(valueName -> valueName.name()).collect(Collectors.toList());
        boolean valid = validator.validateWrites(writeValueNames, this.httpWrites);
        if(! valid) {
            logger.error("{}: Terminating because of incorrect configuration", applianceId);
            System.exit(-1);
        }
    }

    @Override
    public void start(LocalDateTime now, Timer timer) {
    }

    @Override
    public void stop(LocalDateTime now) {
    }

    @Override
    public boolean isOn() {
        if(this.httpRead != null) {
            ParentWithChild<HttpRead, HttpReadValue> onRead = HttpRead.getFirstHttpRead(ControlValueName.On.name(),
                    Collections.singletonList(this.httpRead));
            if(onRead != null) {
                return this.httpHandler.getBooleanValue(onRead, getContentContentProtocolHandler());
            }
        }
        // fall back to internal state if no HttpRead is configured
        return on;
    }

    @Override
    public boolean on(LocalDateTime now, boolean switchOn) {
        logger.info("{}: Switching {}", applianceId, (switchOn ? "on" : "off"));
        ParentWithChild<HttpWrite, HttpWriteValue> write
                = HttpWrite.getFirstHttpWrite(getValueName(switchOn).name(), this.httpWrites);
        if(write != null) {
            HttpMethod httpMethod = write.child().getMethod();
            String data = httpMethod == HttpMethod.POST ? write.child().getValue() : null;
            CloseableHttpResponse response = this.httpTransactionExecutor.executeLeaveOpen(write.child().getMethod(),
                    write.parent().getUrl(), data);
            if(response != null) {
                int statusCode = response.getStatusLine().getStatusCode();
                this.httpTransactionExecutor.closeResponse(response);
                if(statusCode == HttpStatus.SC_OK) {
                    on = switchOn;
                    for(ControlStateChangedListener listener : new ArrayList<>(controlStateChangedListeners)) {
                        listener.controlStateChanged(now, switchOn);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    public ContentProtocolHandler getContentContentProtocolHandler() {
        if(this.contentContentProtocolHandler == null) {
            if(ContentProtocolType.JSON.name().equals(this.contentProtocol)) {
                this.contentContentProtocolHandler = new JsonContentProtocolHandler();
            }
        }
        return this.contentContentProtocolHandler;
    }

    private ControlValueName getValueName(boolean switchOn) {
        return switchOn ? ControlValueName.On : ControlValueName.Off;
    }

    @Override
    public void addControlStateChangedListener(ControlStateChangedListener listener) {
        this.controlStateChangedListeners.add(listener);
    }

    @Override
    public void removeControlStateChangedListener(ControlStateChangedListener listener) {
        this.controlStateChangedListeners.remove(listener);
    }
}
