/**
 * Copyright (C) 2013, Dmitry Holodov. All rights reserved.
 */
package to.noc.devicefp.server.service;

import com.google.web.bindery.requestfactory.server.RequestFactoryServlet;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import to.noc.devicefp.server.domain.entity.Device;
import to.noc.devicefp.server.domain.entity.RequestHeader;
import to.noc.devicefp.server.domain.entity.ZombieCookie;
import to.noc.devicefp.server.domain.repository.DeviceRepository;

@Service /* Spring service */
public class SavedDeviceServiceImpl implements SavedDeviceService {
    private static final Logger log = LoggerFactory.getLogger(SavedDeviceServiceImpl.class);

    @Autowired private DeviceRepository deviceRepository;
    @Autowired private CurrentUserService currentUserService;
    @Autowired private CurrentDeviceService currentDeviceService;


    private String getCookieId() {
        String cookieId = currentDeviceService.getCookieId();
        if (cookieId == null) {
            throw new IllegalStateException("stale session");
        }
        return cookieId;
    }

    private boolean isAuthenticated() {
        return currentUserService.isAuthenticated();
    }

    private boolean isAdmin() {
        return currentUserService.isAdmin();
    }

    private Long getUserId() {
        return currentUserService.getUserId();
    }

    private static String getRemoteIp() {
        return RequestFactoryServlet.getThreadLocalRequest().getRemoteAddr();
    }

    private static void checkBounds(int firstResult, int maxResults) {
        log.debug("checkBounds(firstResult={}, maxResults={})", firstResult, maxResults);
        if (firstResult < 0 || maxResults > 50) {
            // 50 is being generous, as the client UI currently displays far less.
            log.error("Bad bounds request from client: firstResult={}, maxResults={}",
                    firstResult, maxResults);
            throw new IllegalArgumentException("Illegal bounds in device range request");
        }
    }


    @Override
    public long countSaved() {
        long count;

        if (isAuthenticated()) {
            count = deviceRepository.countUserDevices(getUserId());
        } else {
            count = deviceRepository.countLinkedDevices(getCookieId());
        }

        return count;
    }

    @Override
    public List<Device> findSaved(int firstResult, int maxResults) {
        checkBounds(firstResult, maxResults);

        List<Device> devices;
        Long userId = null;

        if (isAuthenticated()) {
            userId = getUserId();
            devices = deviceRepository.findUserDevices(userId, firstResult, maxResults);
        } else {
            devices = deviceRepository.findLinkedDevices(getCookieId(), firstResult, maxResults);
        }

        if (log.isDebugEnabled()) {
            StringBuilder sb =
                    new StringBuilder("findSaved(firstResult=").append(firstResult)
                    .append(", maxResults=").append(maxResults)
                    .append(", userId=").append(userId != null ? userId : "anon")
                    .append(": ");
            for (Device d : devices) {
                sb.append(d.getId()).append(",");
            }
            sb.setLength(sb.length() - 1);
            log.debug(sb.toString());
        }

        return devices;
    }


    @Override
    public long countWithSameIp() {
        return deviceRepository.countWithIpDevices(getRemoteIp());
    }

    @Override
    public List<Device> findWithSameIp(int firstResult, int maxResults) {
        checkBounds(firstResult, maxResults);

        List<Device> devices =
                deviceRepository.findWithIpDevices(getRemoteIp(), firstResult, maxResults);
        sanitizeDevices(devices);

        if (log.isDebugEnabled()) {
            StringBuilder sb =
                    new StringBuilder("findWithSameIp(firstResult=").append(firstResult)
                    .append(", maxResults=").append(maxResults)
                    .append(": ");
            for (Device d : devices) {
                sb.append(d.getId()).append(",");
            }
            sb.setLength(sb.length() - 1);
            log.debug(sb.toString());
        }

        return devices;
    }


    @Override
    public long countAllOther() {
        long count = 0;
        if (isAdmin()) {
            count = deviceRepository.countAllDevicesButMine(getUserId());
        }
        return count;
    }


    @Override
    public List<Device> findAllOther(int firstResult, int maxResults) {
        List<Device> devices;

        if (isAdmin()) {
            devices = deviceRepository.findAllDevicesButMine(getUserId(), firstResult, maxResults);
        } else {
            log.error("request to findAll from non-admin user: client={}",
                    currentDeviceService.getClientInfo());
            devices = new ArrayList<Device>();
        }
        return devices;
    }


    //
    //  Remove cookie information from devices have the same IP address
    //  as the current request, but might not have the same owner.  Ensure
    //  that there is no transaction so our entity is detached and will not
    //  have its changes saved back to the database.  "remember-me" and session
    //  cookies are sterilized from the request headers before they enter the
    //  database.
    //
    @Transactional(propagation = Propagation.NEVER)
    private void sanitizeDevice(Device device) {

        for (RequestHeader rh: device.getRequestHeaders()) {
            String name = rh.getName();
            switch (name) {
                case "If-None-Match":
                case "Cookie":
                    // Our UUID strings have 5 lower case hex strings
                    // separated by 4 dashes.  Replace everthing after the
                    // first dash with "...".
                    rh.setValue(rh.getValue().replaceAll("(-[0-9a-f]+){4}", "-[...]"));
                    break;
                default:
                    break;
            }
        }

        ZombieCookie cookie = device.getZombieCookie();
        cookie.setId(cookie.getId().replaceFirst("-.*", "-[...]"));
    }

    private void sanitizeDevices(List<Device> devices) {
        String currentCookieId = getCookieId();
        for (Device device: devices) {
            if (!currentCookieId.equals(device.getZombieCookie().getId())) {
                sanitizeDevice(device);
            }
        }
    }


    @Transactional
    @Override
    public void markDeleted(Long targetDeviceId) {
        String clientInfo = currentDeviceService.getClientInfo();

        if (targetDeviceId == null) {
            log.error("markDeleted called with null deviceId: client={}", clientInfo);
            throw new IllegalArgumentException("null device id");
        }

        Device targetDevice;
        if (isAuthenticated()) {
            targetDevice = deviceRepository.findUserDevice(getUserId(), targetDeviceId);
        } else {
            targetDevice = deviceRepository.findLinkedDevice(getCookieId(), targetDeviceId);
        }

        if (targetDevice == null) {
            log.error("markDeleted called on unknown or non-linked deviceId={}: client={}",
                    targetDeviceId, clientInfo);
            return;
        }

        if (targetDevice.isMarkedDeleted()) {
            log.error("deviceId={} was already marked as deleted: client={}",
                    targetDeviceId, clientInfo);
            return;
        }

        targetDevice.setMarkedDeleted(true);
        try {
            deviceRepository.save(targetDevice);
            log.debug("deviceId={} successfully marked as deleted: client={}",
                    targetDeviceId, clientInfo);
        } catch (org.springframework.dao.DataAccessException ex) {
            log.error("error marking device as deleted", ex);
        }
    }

}
