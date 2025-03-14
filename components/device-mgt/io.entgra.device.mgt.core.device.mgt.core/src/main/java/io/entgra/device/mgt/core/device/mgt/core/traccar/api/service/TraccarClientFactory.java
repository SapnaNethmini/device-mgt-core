/*
 * Copyright (c) 2018 - 2023, Entgra (Pvt) Ltd. (http://www.entgra.io) All Rights Reserved.
 *
 * Entgra (Pvt) Ltd. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.entgra.device.mgt.core.device.mgt.core.traccar.api.service;

import io.entgra.device.mgt.core.device.mgt.common.TrackerDeviceInfo;
import io.entgra.device.mgt.core.device.mgt.common.TrackerGroupInfo;
import io.entgra.device.mgt.core.device.mgt.common.TrackerPermissionInfo;
import io.entgra.device.mgt.core.device.mgt.common.exceptions.TrackerAlreadyExistException;
import io.entgra.device.mgt.core.device.mgt.common.exceptions.TransactionManagementException;
import io.entgra.device.mgt.core.device.mgt.core.dao.TrackerDAO;
import io.entgra.device.mgt.core.device.mgt.core.dao.TrackerManagementDAOException;
import io.entgra.device.mgt.core.device.mgt.core.dao.TrackerManagementDAOFactory;
import io.entgra.device.mgt.core.device.mgt.core.traccar.api.service.impl.DeviceAPIClientServiceImpl;
import io.entgra.device.mgt.core.device.mgt.core.traccar.common.TraccarHandlerConstants;
import io.entgra.device.mgt.core.device.mgt.core.traccar.common.beans.TraccarDevice;
import io.entgra.device.mgt.core.device.mgt.core.traccar.common.beans.TraccarGroups;
import io.entgra.device.mgt.core.device.mgt.core.traccar.common.beans.TraccarPosition;
import io.entgra.device.mgt.core.device.mgt.core.traccar.common.beans.TraccarUser;
import io.entgra.device.mgt.core.device.mgt.core.traccar.common.config.TraccarGateway;
import io.entgra.device.mgt.core.device.mgt.core.traccar.common.util.TraccarUtil;
import io.entgra.device.mgt.core.device.mgt.core.traccar.core.config.TraccarConfigurationManager;
import io.entgra.device.mgt.core.device.mgt.core.util.HttpReportingUtil;
import okhttp3.*;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.wso2.carbon.context.PrivilegedCarbonContext;

import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class TraccarClientFactory {

    private static TraccarClientFactory INSTANCE;
    private static final Log log = LogFactory.getLog(TraccarClientFactory.class);
    private static final int THREAD_POOL_SIZE = 50;
    private final OkHttpClient client;
    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    final TraccarGateway traccarGateway = getTraccarGateway();
    final String endpoint = traccarGateway.getPropertyByName(TraccarHandlerConstants.TraccarConfig.ENDPOINT).getValue();
    final String authorization = traccarGateway.getPropertyByName(TraccarHandlerConstants.TraccarConfig.AUTHORIZATION).getValue();
    final String authorizationKey = traccarGateway.getPropertyByName(TraccarHandlerConstants.TraccarConfig.AUTHORIZATION_KEY).getValue();
    final String defaultPort = traccarGateway.getPropertyByName(TraccarHandlerConstants.TraccarConfig.DEFAULT_PORT).getValue();
    final String locationUpdatePort = traccarGateway.getPropertyByName(TraccarHandlerConstants.TraccarConfig.LOCATION_UPDATE_PORT).getValue();

    final String username = PrivilegedCarbonContext.getThreadLocalCarbonContext().getUsername();
    private final TrackerDAO trackerDAO;

    /**
     * This is the private constructor method as this class is a singleton
     */
    private TraccarClientFactory() {
        client = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .connectionPool(new ConnectionPool(100, 50, TimeUnit.SECONDS))
                .build();
        this.trackerDAO = TrackerManagementDAOFactory.getTrackerDAO();
    }

    /**
     * This method is used to initiate a singleton instance of this class.
     *
     * @return TraccarClientFactory instance
     */
    public static TraccarClientFactory getInstance() {
        if(INSTANCE == null) {
            INSTANCE = new TraccarClientFactory();
        }
        return INSTANCE;
    }

    private class OkHttpClientThreadPool implements Callable<String> {
        final String publisherUrlWithContext;
        final JSONObject payload;
        private final String method;
        private String authorizeKey;
        private String serverUri;

        private OkHttpClientThreadPool(String publisherUrlWithContext, JSONObject payload, String method,
                                       String authorizeKey, String serverUri) {
            this.publisherUrlWithContext = publisherUrlWithContext;
            this.payload = payload;
            this.method = method;
            this.authorizeKey = authorizeKey;
            this.serverUri = serverUri;
        }

        @Override
        public String call() throws IOException {
            RequestBody requestBody;
            Request.Builder builder = new Request.Builder();
            Request request;
            Response response;

            if (Objects.equals(method, TraccarHandlerConstants.Methods.POST)) {
                requestBody = RequestBody.create(payload.toString(), MediaType.parse("application/json; charset=utf-8"));
                builder = builder.post(requestBody);
            } else if (Objects.equals(method, TraccarHandlerConstants.Methods.PUT)) {
                requestBody = RequestBody.create(payload.toString(), MediaType.parse("application/json; charset=utf-8"));
                builder = builder.put(requestBody);
            } else if (Objects.equals(method, TraccarHandlerConstants.Methods.DELETE)) {
                if (publisherUrlWithContext.contains("permission")) {
                    requestBody = RequestBody.create(payload.toString(), MediaType.parse("application/json; charset=utf-8"));
                    builder = builder.delete(requestBody);
                } else {
                    builder = builder.delete();
                }
            }

            request = builder.url(serverUri + publisherUrlWithContext).addHeader(authorization, authorizeKey).build();
            response = client.newCall(request).execute();
            return response.body().string();
        }
    }

    public String fetchAllUsers() throws ExecutionException, InterruptedException {
        String url = defaultPort + "/api/users";

        Future<String> result = executor.submit(new OkHttpClientThreadPool(url, null, TraccarHandlerConstants.Methods.GET,
                authorizedKey(HttpReportingUtil.trackerUser(), HttpReportingUtil.trackerPassword()),
                serverUrl(HttpReportingUtil.trackerServer())));
        return result.get();
    }

    public String fetchUserInfo(String userName) throws ExecutionException, InterruptedException {
        String allUsers = fetchAllUsers(); //get all users
        JSONArray fetchAllUsers = new JSONArray(allUsers); //loop users
        for (int i = 0; i < fetchAllUsers.length(); i++) {
            // if login is null then check the name or if login is not null then check the login
            if (fetchAllUsers.getJSONObject(i).isNull("login")) {
                if (Objects.equals(fetchAllUsers.getJSONObject(i).getString("name"), userName)) {
                    return fetchAllUsers.getJSONObject(i).toString();
                }
            } else {
                if (Objects.equals(fetchAllUsers.getJSONObject(i).getString("login"), userName) ||
                        Objects.equals(fetchAllUsers.getJSONObject(i).getString("name"), userName)) {
                    return fetchAllUsers.getJSONObject(i).toString();
                }
            }
        }
        return TraccarHandlerConstants.Types.USER_NOT_FOUND;
    }

    public String returnUser(String userName) throws TrackerManagementDAOException {
        try {
            String result = fetchUserInfo(userName);
            Date today = new Date();
            LocalDateTime tomorrow = LocalDateTime.from(today.toInstant().atZone(ZoneId.of("UTC"))).plusDays(1);
            String token = DeviceAPIClientServiceImpl.generateRandomString(TraccarHandlerConstants.Types.TRACCAR_TOKEN);

            TraccarUser traccarUser = new TraccarUser();
            traccarUser.setToken(token);

            if (Objects.equals(result, TraccarHandlerConstants.Types.USER_NOT_FOUND)) {
                //create user
                log.info("Creating a user on Traccar client");
                traccarUser.setName(userName);
                traccarUser.setLogin(userName);
                traccarUser.setEmail(userName);
                traccarUser.setPassword(DeviceAPIClientServiceImpl.generateRandomString(TraccarHandlerConstants.Types.DEFAULT_RANDOM));
                traccarUser.setDeviceLimit(-1);
                traccarUser.setExpirationTime(tomorrow.toString());
                DeviceAPIClientServiceImpl.createUser(traccarUser);
            } else {
                //update user
                log.info("Updating the user on Traccar client");
                JSONObject obj = new JSONObject(result);

                traccarUser.setId(obj.getInt("id"));
                traccarUser.setName(obj.getString("name"));
                if (!obj.isNull("login")) {
                    traccarUser.setLogin(obj.getString("login"));
                }
                traccarUser.setEmail(obj.getString("email"));
                traccarUser.setDeviceLimit(obj.getInt("deviceLimit"));
                traccarUser.setUserLimit(obj.getInt("userLimit"));
                traccarUser.setAdministrator(obj.getBoolean("administrator"));
                traccarUser.setDisabled(obj.getBoolean("disabled"));
                traccarUser.setReadonly(obj.getBoolean("readonly"));
                if (!obj.getBoolean("administrator")) {
                    traccarUser.setExpirationTime(tomorrow.toString());
                } else if (!obj.isNull("expirationTime")) {
                    traccarUser.setExpirationTime(obj.getString("expirationTime"));
                }
                DeviceAPIClientServiceImpl.updateUser(traccarUser, obj.getInt("id"));
            }
            result = fetchUserInfo(userName);
            return result;
        } catch (InterruptedException | ExecutionException e) {
            JSONObject obj = new JSONObject();
            String msg = "Error occurred while executing enrollment status of the device.";
            obj.put("error", msg);
            obj.put("e", e);
            log.error(msg, e);
            return obj.toString();
        }
    }

    public String createUser(TraccarUser traccarUser) throws ExecutionException, InterruptedException {
        String url = defaultPort + "/api/users";
        JSONObject payload = TraccarUtil.TraccarUserPayload(traccarUser);

        Future<String> res = executor.submit(new OkHttpClientThreadPool(url, payload, TraccarHandlerConstants.Methods.POST,
                authorizedKey(HttpReportingUtil.trackerUser(), HttpReportingUtil.trackerPassword()),
                serverUrl(HttpReportingUtil.trackerServer())));
        return res.get();
    }

    public String updateUser(TraccarUser traccarUser, int userId) throws ExecutionException, InterruptedException {
        String url = defaultPort + "/api/users/" + userId;
        JSONObject payload = TraccarUtil.TraccarUserPayload(traccarUser);

        Future<String> res = executor.submit(new OkHttpClientThreadPool(url, payload, TraccarHandlerConstants.Methods.PUT,
                authorizedKey(HttpReportingUtil.trackerUser(), HttpReportingUtil.trackerPassword()),
                serverUrl(HttpReportingUtil.trackerServer())));
        return res.get();
    }

    public void setPermission(int userId, int deviceId)
            throws ExecutionException, InterruptedException, TrackerManagementDAOException {
        JSONObject payload = new JSONObject();
        payload.put("userId", userId);
        payload.put("deviceId", deviceId);

        String url = defaultPort + "/api/permissions";

        Future<String> res = executor.submit(new OkHttpClientThreadPool(url, payload, TraccarHandlerConstants.Methods.POST,
                authorizedKey(HttpReportingUtil.trackerUser(), HttpReportingUtil.trackerPassword()),
                serverUrl(HttpReportingUtil.trackerServer())));
        String result = res.get();

        if (("").equals(result)) {
            try {
                TrackerManagementDAOFactory.beginTransaction();
                trackerDAO.addTrackerUserDevicePermission(userId, deviceId);
                TrackerManagementDAOFactory.commitTransaction();
            } catch (TrackerManagementDAOException e) {
                TrackerManagementDAOFactory.rollbackTransaction();
                String msg = "Error occurred while mapping with deviceId .";
                log.error(msg, e);
                throw new TrackerManagementDAOException(msg, e);
            } catch (TransactionManagementException e) {
                TrackerManagementDAOFactory.rollbackTransaction();
                String msg = "Error occurred establishing the DB connection .";
                log.error(msg, e);
                throw new TrackerManagementDAOException(msg, e);
            } finally {
                TrackerManagementDAOFactory.closeConnection();
            }
        } else {
            String msg = "Couldn't add the permission record: " + result;
            log.error(msg);
            throw new TrackerManagementDAOException(msg);
        }
    }

    public void removePermission(int userId, int deviceId, int removeType)
            throws TrackerManagementDAOException, ExecutionException, InterruptedException {
        JSONObject payload = new JSONObject();
        payload.put("userId", userId);
        payload.put("deviceId", deviceId);

        String url = defaultPort + "/api/permissions";

        Future<String> res = executor.submit(new OkHttpClientThreadPool(url, payload, TraccarHandlerConstants.Methods.DELETE,
                authorizedKey(HttpReportingUtil.trackerUser(), HttpReportingUtil.trackerPassword()),
                serverUrl(HttpReportingUtil.trackerServer())));

        if (res.get() != null) {
            try {
                TrackerManagementDAOFactory.beginTransaction();
                trackerDAO.removeTrackerUserDevicePermission(deviceId, userId, removeType);
                TrackerManagementDAOFactory.commitTransaction();
            } catch (TrackerManagementDAOException e) {
                TrackerManagementDAOFactory.rollbackTransaction();
                String msg = "Error occurred while mapping with deviceId .";
                log.error(msg, e);
                throw new TrackerManagementDAOException(msg, e);
            } catch (TransactionManagementException e) {
                TrackerManagementDAOFactory.rollbackTransaction();
                String msg = "Error occurred establishing the DB connection .";
                log.error(msg, e);
                throw new TrackerManagementDAOException(msg, e);
            } finally {
                TrackerManagementDAOFactory.closeConnection();
            }
        } else {
            String msg = "Error occured while removing permission.";
            log.error(msg);
            throw new TrackerManagementDAOException(msg);
        }
    }

    public List<TrackerPermissionInfo> getUserIdofPermissionByUserIdNIdList(int userId, List<Integer> NotInDeviceIdList)
            throws TrackerManagementDAOException {
        try {
            TrackerManagementDAOFactory.openConnection();
            return trackerDAO.getUserIdofPermissionByUserIdNIdList(userId, NotInDeviceIdList);
        } catch (TrackerManagementDAOException e) {
            String msg = "Error occurred while mapping with deviceId .";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } catch (SQLException e) {
            String msg = "Error occurred establishing the DB connection .";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } finally {
            TrackerManagementDAOFactory.closeConnection();
        }
    }

    public TrackerDeviceInfo getTrackerDevice(int deviceId, int tenantId) throws TrackerManagementDAOException {
        try {
            TrackerManagementDAOFactory.openConnection();
            TrackerDeviceInfo trackerDeviceInfo = trackerDAO.getTrackerDevice(deviceId, tenantId);
            if(trackerDeviceInfo == null) {
                String msg = "No tracker device is found upon the device ID of: " + deviceId;
                if (log.isDebugEnabled()) {
                    log.debug(msg);
                }
                return null;
            } else {
                return trackerDeviceInfo;
            }
        } catch (SQLException e) {
            String msg = "Error occurred establishing the DB connection .";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } catch (TrackerManagementDAOException e) {
            String msg = "Could not add new device location";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } finally {
            TrackerManagementDAOFactory.closeConnection();
        }
    }

    public boolean getUserIdofPermissionByDeviceIdNUserId(int deviceId, int userId)
            throws TrackerManagementDAOException {
        Boolean result = false;
        try {
            TrackerManagementDAOFactory.openConnection();
            result = trackerDAO.getUserIdofPermissionByDeviceIdNUserId(deviceId, userId);
        } catch (TrackerManagementDAOException e) {
            String msg = "Error occurred while mapping with deviceId .";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } catch (SQLException e) {
            String msg = "Error occurred establishing the DB connection .";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } finally {
            TrackerManagementDAOFactory.closeConnection();
        }
        return result;
    }

    /**
     * Add Traccar Device operation.
     *
     * @param traccarDevice with DeviceName UniqueId, Status, Disabled LastUpdate, PositionId, GroupId
     *                      Model, Contact, Category, fenceIds
     * @throws TrackerManagementDAOException Failed while add Traccar Device the operation
     */
    public void addDevice(TraccarDevice traccarDevice, int tenantId) throws
            TrackerManagementDAOException, TrackerAlreadyExistException, ExecutionException, InterruptedException {
        TrackerDeviceInfo trackerDeviceInfo = null;
        try {
            TrackerManagementDAOFactory.openConnection();
            trackerDeviceInfo = trackerDAO.getTrackerDevice(traccarDevice.getId(), tenantId);
        } catch (TrackerManagementDAOException e) {
            String msg = "Error occurred while mapping with deviceId .";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } catch (SQLException e) {
            String msg = "Error occurred establishing the DB connection .";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } finally {
            TrackerManagementDAOFactory.closeConnection();
        }

        if (trackerDeviceInfo == null) {
            //add the device
            String url = defaultPort + "/api/devices";
            JSONObject payload = TraccarUtil.TraccarDevicePayload(traccarDevice);

            Future<String> res = executor.submit(new OkHttpClientThreadPool(url, payload, TraccarHandlerConstants.Methods.POST,
                    authorizedKey(HttpReportingUtil.trackerUser(), HttpReportingUtil.trackerPassword()),
                    serverUrl(HttpReportingUtil.trackerServer())));
            String result = res.get();
            log.info("Device " + traccarDevice.getDeviceIdentifier() + " has been added to Traccar.");
            if (res.isDone() && result.charAt(0) == '{') {
                JSONObject obj = new JSONObject(result);
                if (obj.has("id")) {
                    int traccarDeviceId = obj.getInt("id");
                    int deviceId = traccarDevice.getId();
                    log.info("TraccarDeviceId - " + traccarDeviceId);
                    try {
                        TrackerManagementDAOFactory.beginTransaction();
                        trackerDAO.addTrackerDevice(traccarDeviceId, deviceId, tenantId);
                        trackerDeviceInfo = trackerDAO.getTrackerDevice(deviceId, tenantId);
                        if (trackerDeviceInfo != null && trackerDeviceInfo.getStatus() == 0) {
                            trackerDAO.updateTrackerDeviceIdANDStatus(trackerDeviceInfo.getTraccarDeviceId(), deviceId, tenantId, 1);
                        }
                        TrackerManagementDAOFactory.commitTransaction();
                    } catch (TrackerManagementDAOException e) {
                        TrackerManagementDAOFactory.rollbackTransaction();
                        String msg = "Error occurred while mapping with deviceId .";
                        log.error(msg, e);
                        throw new TrackerManagementDAOException(msg, e);
                    } catch (TransactionManagementException e) {
                        TrackerManagementDAOFactory.rollbackTransaction();
                        String msg = "Error occurred establishing the DB connection .";
                        log.error(msg, e);
                        throw new TrackerManagementDAOException(msg, e);
                    } finally {
                        TrackerManagementDAOFactory.closeConnection();
                    }

                    JSONObject returnUserInfo = new JSONObject(returnUser(username));
                    setPermission(returnUserInfo.getInt("id"), traccarDeviceId);
                }
            }
        } else {
            String msg = "The device already exist";
            log.error(msg);
            throw new TrackerAlreadyExistException(msg);
        }
    }

    /**
     * Modify the Device
     *
     * @param traccarDevice with DeviceName UniqueId, Status, Disabled LastUpdate, PositionId, GroupId
     *                      Model, Contact, Category, fenceIds
     * @throws TrackerManagementDAOException Failed while add Traccar Device the operation
     */
    public void modifyDevice(TraccarDevice traccarDevice, int tenantId) throws TrackerManagementDAOException, ExecutionException, InterruptedException {
        TrackerDeviceInfo trackerDeviceInfo = null;
        try {
            TrackerManagementDAOFactory.openConnection();
            trackerDeviceInfo = trackerDAO.getTrackerDevice(traccarDevice.getId(), tenantId);
        } catch (TrackerManagementDAOException e) {
            String msg = "Error occurred while mapping with deviceId .";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } catch (SQLException e) {
            String msg = "Error occurred establishing the DB connection .";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } finally {
            TrackerManagementDAOFactory.closeConnection();
        }
        if (trackerDeviceInfo != null) {
            log.info("Preparing to rename device (" + traccarDevice.getId() + " to taccar server");
            String url = defaultPort + "/api/devices/" + trackerDeviceInfo.getTraccarDeviceId();
            JSONObject payload = TraccarUtil.TraccarDevicePayload(traccarDevice, trackerDeviceInfo.getTraccarDeviceId());
            Future<String> res = executor.submit(new OkHttpClientThreadPool(url, payload, TraccarHandlerConstants.Methods.PUT,
                    authorizedKey(HttpReportingUtil.trackerUser(), HttpReportingUtil.trackerPassword()),
                    serverUrl(HttpReportingUtil.trackerServer())));
            String result = res.get();
            log.info("Device " + traccarDevice.getDeviceIdentifier() + " has been added to Traccar.");
            if (res.isDone() && result.charAt(0) == '{') {
                log.info("Succesfully renamed Traccar Device - " + traccarDevice.getId() + "in server");
            } else {
                log.info("Failed to rename Traccar Device" + traccarDevice.getId() + "in server");
            }
        } else {
//            forward to add device
            try {
                addDevice(traccarDevice, tenantId);
            } catch (TrackerAlreadyExistException e) {
                String msg = "The device already exist";
                log.error(msg, e);
            }
        }
    }

    /**
     * Add Device GPS Location operation.
     *
     * @param deviceInfo with DeviceIdentifier, Timestamp, Lat, Lon, Bearing, Speed, ignition
     */
    public void updateLocation(TraccarDevice device, TraccarPosition deviceInfo, int tenantId) throws
            TrackerManagementDAOException, TrackerAlreadyExistException, ExecutionException, InterruptedException {
        TrackerDeviceInfo trackerDeviceInfo = null;
        try {
            TrackerManagementDAOFactory.openConnection();
            trackerDeviceInfo = trackerDAO.getTrackerDevice(device.getId(), tenantId);
        } catch (SQLException e) {
            String msg = "Error occurred establishing the DB connection .";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } catch (TrackerManagementDAOException e) {
            String msg = "Could not add new device location";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } finally {
            TrackerManagementDAOFactory.closeConnection();
        }


        //check if the device is already exist before updating the location
        if (trackerDeviceInfo == null) {
            //add device if not exist
            addDevice(device, tenantId);
        } else {
            //Update Location
            if (log.isDebugEnabled()) {
                log.info("Updating Location of the device: " + device.getId());
            }
            String url = locationUpdatePort + "/?id=" + deviceInfo.getDeviceIdentifier() +
                    "&timestamp=" + deviceInfo.getTimestamp() + "&lat=" + deviceInfo.getLat() +
                    "&lon=" + deviceInfo.getLon() + "&bearing=" + deviceInfo.getBearing() +
                    "&speed=" + deviceInfo.getSpeed() + "&ignition=true";

            executor.submit(new OkHttpClientThreadPool(url, null, TraccarHandlerConstants.Methods.GET,
                    authorizedKey(HttpReportingUtil.trackerUser(), HttpReportingUtil.trackerPassword()),
                    Objects.requireNonNull(HttpReportingUtil.trackerServer()).split(":")[0] + ":" +
                            Objects.requireNonNull(HttpReportingUtil.trackerServer()).split(":")[1] + ":"));
        }
    }

    /**
     * Dis-enroll a Device operation.
     *
     * @param deviceId identified via deviceIdentifier
     * @throws TrackerManagementDAOException Failed while dis-enroll a Traccar Device operation
     */
    public void disEnrollDevice(int deviceId, int tenantId) throws TrackerManagementDAOException, ExecutionException, InterruptedException {
        TrackerDeviceInfo trackerDeviceInfo = null;
        List<TrackerPermissionInfo> trackerPermissionInfo = null;
        try {
            TrackerManagementDAOFactory.beginTransaction();
            try {
                TrackerManagementDAOFactory.openConnection();
                trackerDeviceInfo = trackerDAO.getTrackerDevice(deviceId, tenantId);
                if (trackerDeviceInfo != null) {
                    trackerDAO.removeTrackerDevice(deviceId, tenantId);
                    TrackerManagementDAOFactory.commitTransaction();
                    trackerPermissionInfo = trackerDAO.getUserIdofPermissionByDeviceId(trackerDeviceInfo.getTraccarDeviceId());
                } else {
                    String msg = "Tracker device for device id " + deviceId +  " not found";
                    log.error(msg);
                    throw new TrackerManagementDAOException(msg);
                }
            } catch (SQLException e) {
                String msg = "Error occurred establishing the DB connection .";
                log.error(msg, e);
                throw new TrackerManagementDAOException(msg, e);
            } catch (TrackerManagementDAOException e) {
                String msg = "Could not add new device location";
                log.error(msg, e);
                throw new TrackerManagementDAOException(msg, e);
            } finally {
                TrackerManagementDAOFactory.closeConnection();
            }
        } catch (TransactionManagementException e) {
            TrackerManagementDAOFactory.rollbackTransaction();
            String msg = "Error occurred establishing the DB connection";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        }
        log.info("Disenrolling Device with device id " + deviceId + " from traccar client");
        //Delete from traccar
        if (trackerDeviceInfo != null) {

            if (trackerPermissionInfo.size() > 0) {
                String url = defaultPort + "/api/devices/" + trackerPermissionInfo.get(0).getTraccarDeviceId();

                executor.submit(new OkHttpClientThreadPool(url, null, TraccarHandlerConstants.Methods.DELETE,
                        authorizedKey(HttpReportingUtil.trackerUser(), HttpReportingUtil.trackerPassword()),
                        serverUrl(HttpReportingUtil.trackerServer())));
            } else {
                String msg = "Tracker permission mapping info not found";
                log.error(msg);
                throw new TrackerManagementDAOException(msg);
            }
            //remove permissions
            try {
                removePermission(
                        trackerPermissionInfo.get(0).getTraccarUserId(),
                        trackerPermissionInfo.get(0).getTraccarDeviceId(),
                        TraccarHandlerConstants.Types.REMOVE_TYPE_MULTIPLE);
            } catch (ExecutionException e) {
                String msg = "Error occured while removing tacker permissions ";
                log.error(msg);
                throw new ExecutionException(msg, e);
            }
        } else {
            String msg = "Tracker device not found";
            log.error(msg);
            throw new TrackerManagementDAOException(msg);
        }
    }

    /**
     * Add Traccar Device operation.
     *
     * @param groupInfo with groupName
     * @throws TrackerManagementDAOException Failed while add Traccar Device the operation
     */
    public void addGroup(TraccarGroups groupInfo, int groupId, int tenantId) throws
            TrackerManagementDAOException, TrackerAlreadyExistException, ExecutionException, InterruptedException {
        TrackerGroupInfo trackerGroupInfo = null;
        try {
            TrackerManagementDAOFactory.openConnection();
            trackerGroupInfo = trackerDAO.getTrackerGroup(groupId, tenantId);
            if (trackerGroupInfo != null) {
                String msg = "The group already exists in Traccar.";
                log.error(msg);
                throw new TrackerAlreadyExistException(msg);
            }
        } catch (TrackerManagementDAOException e) {
            String msg = "Error occurred while mapping with groupId.";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } catch (SQLException e) {
            String msg = "Error occurred establishing the DB connection.";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } finally {
            TrackerManagementDAOFactory.closeConnection();
        }

        JSONObject payload = new JSONObject();
        payload.put("name", groupInfo.getName());
        payload.put("attributes", new JSONObject());

        String url = defaultPort + "/api/groups";

        Future<String> res = executor.submit(new OkHttpClientThreadPool(url, payload, TraccarHandlerConstants.Methods.POST,
                authorizedKey(HttpReportingUtil.trackerUser(), HttpReportingUtil.trackerPassword()),
                serverUrl(HttpReportingUtil.trackerServer())));
        String result = res.get();
        if (null != trackerGroupInfo) {
            log.info("Group " + trackerGroupInfo.getGroupId() + " has been added to Traccar.");
        }
        if (res.isDone() && result.charAt(0) == '{') {
            JSONObject obj = new JSONObject(result);
            if (obj.has("id")) {
                int traccarGroupId = obj.getInt("id");
                try {
                    TrackerManagementDAOFactory.beginTransaction();
                    trackerDAO.addTrackerGroup(traccarGroupId, groupId, tenantId);
                    trackerGroupInfo = trackerDAO.getTrackerGroup(groupId, tenantId);
                    if (trackerGroupInfo.getStatus() == 0) {
                        trackerDAO.updateTrackerGroupIdANDStatus(trackerGroupInfo.getTraccarGroupId(), groupId, tenantId, 1);
                        TrackerManagementDAOFactory.commitTransaction();
                    }
                } catch (TransactionManagementException e) {
                    String msg = "Error occurred establishing the DB connection. ";
                    log.error(msg, e);
                    TrackerManagementDAOFactory.rollbackTransaction();
                    throw new TrackerManagementDAOException(msg, e);
                } catch (TrackerManagementDAOException e) {
                    TrackerManagementDAOFactory.rollbackTransaction();
                    String msg = "Error occurred while mapping with deviceId. ";
                    log.error(msg, e);
                    throw new TrackerManagementDAOException(msg, e);
                }  finally {
                    TrackerManagementDAOFactory.closeConnection();
                }
            } else {
                // TODO: Assumed the error message change if wrong
                log.error("Response does not contains the key id: " + result);
            }
        } else {
            // TODO: Assumed the error message change if wrong
            log.error("Response does not contains a JSON object " + result);
        }
    }

    /**
     * update Traccar Group operation.
     *
     * @param groupInfo with groupName
     * @throws TrackerManagementDAOException Failed while add Traccar Device the operation
     */
    public void updateGroup(TraccarGroups groupInfo, int groupId, int tenantId) throws
            TrackerManagementDAOException, TrackerAlreadyExistException, ExecutionException, InterruptedException {
        TrackerGroupInfo res = null;
        try {
            TrackerManagementDAOFactory.openConnection();
            res = trackerDAO.getTrackerGroup(groupId, tenantId);
        } catch (SQLException e) {
            String msg = "Error occurred establishing the DB connection. ";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } catch (TrackerManagementDAOException e) {
            String msg = "Could not find traccar group details. ";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } finally {
            TrackerManagementDAOFactory.closeConnection();
        }

        if ((res == null) || (res.getTraccarGroupId() == 0)) {
            //add a new traccar group
            addGroup(groupInfo, groupId, tenantId);
        } else if (res != null && (res.getTraccarGroupId() != 0 && res.getStatus() == 0)) {
            //update the traccargroupId and status
            try {
                TrackerManagementDAOFactory.beginTransaction();
                trackerDAO.updateTrackerGroupIdANDStatus(res.getTraccarGroupId(), groupId, tenantId, 1);
                TrackerManagementDAOFactory.commitTransaction();
            } catch (TrackerManagementDAOException e) {
                TrackerManagementDAOFactory.rollbackTransaction();
                String msg = "Could not update the traccar group. ";
                log.error(msg, e);
                throw new TrackerManagementDAOException(msg, e);
            } catch (TransactionManagementException e) {
                TrackerManagementDAOFactory.rollbackTransaction();
                String msg = "Error occurred establishing the DB connection. ";
                log.error(msg, e);
                throw new TrackerManagementDAOException(msg, e);
            } finally {
                TrackerManagementDAOFactory.closeConnection();
            }
        } else {
            JSONObject obj = new JSONObject(res);
            JSONObject payload = new JSONObject();
            payload.put("id", obj.getInt("traccarGroupId"));
            payload.put("name", groupInfo.getName());
            payload.put("attributes", new JSONObject());

            String url = defaultPort + "/api/groups/" + obj.getInt("traccarGroupId");

            executor.submit(new OkHttpClientThreadPool(url, payload, TraccarHandlerConstants.Methods.PUT,
                    authorizedKey(HttpReportingUtil.trackerUser(), HttpReportingUtil.trackerPassword()),
                    serverUrl(HttpReportingUtil.trackerServer())));
        }
    }

    /**
     * Add Traccar Device operation.
     *
     * @param groupId
     * @throws TrackerManagementDAOException Failed while add Traccar Device the operation
     */
    public void deleteGroup(int groupId, int tenantId) throws TrackerManagementDAOException, ExecutionException, InterruptedException {
        TrackerGroupInfo res = null;
        JSONObject obj = null;
        try {
            TrackerManagementDAOFactory.beginTransaction();
            res = trackerDAO.getTrackerGroup(groupId, tenantId);
            if (res != null) {
                obj = new JSONObject(res);
                if (obj != null) {
                    trackerDAO.removeTrackerGroup(obj.getInt("id"));
                    String url = defaultPort + "/api/groups/" + obj.getInt("traccarGroupId");
                    executor.submit(new OkHttpClientThreadPool(url, null, TraccarHandlerConstants.Methods.DELETE,
                            authorizedKey(HttpReportingUtil.trackerUser(), HttpReportingUtil.trackerPassword()),
                            serverUrl(HttpReportingUtil.trackerServer())));
                    TrackerManagementDAOFactory.commitTransaction();
                } else {
                    String msg = "Tracker group JSON object is null";
                    log.error(msg);
                    throw new TrackerManagementDAOException(msg);
                }
            } else {
                String msg = "Tracker group not found";
                log.error(msg);
                throw new TrackerManagementDAOException(msg);
            }
        } catch (TransactionManagementException e) {
            TrackerManagementDAOFactory.rollbackTransaction();
            String msg = "Error occurred establishing the DB connection. ";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } catch (TrackerManagementDAOException e) {
            TrackerManagementDAOFactory.rollbackTransaction();
            String msg = "Error occurred while mapping with groupId. ";
            log.error(msg, e);
            throw new TrackerManagementDAOException(msg, e);
        } finally {
            TrackerManagementDAOFactory.closeConnection();
        }
    }

    public String generateRandomString(int len) {
        String chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        Random rnd = new Random();
        return IntStream.range(0, len).mapToObj(i -> String.valueOf(chars.charAt(rnd.nextInt(chars.length())))).collect(Collectors.joining());
    }

    private TraccarGateway getTraccarGateway() {
        return TraccarConfigurationManager.getInstance().getTraccarConfig().getTraccarGateway(
                TraccarHandlerConstants.TraccarConfig.GATEWAY_NAME);
    }

    public String authorizedKey(String trackerUser, String trackerPassword) {
        String newAuthorizationKey = authorizationKey;
        if (trackerUser != null && trackerPassword != null) {
            newAuthorizationKey = trackerUser + ':' + trackerPassword;

            byte[] result = newAuthorizationKey.getBytes();
            byte[] res = Base64.encodeBase64(result);
            newAuthorizationKey = "Basic " + new String(res);
        }
        return newAuthorizationKey;
    }

    public String serverUrl(String serverUrl) {
        if (serverUrl != null) {
            return serverUrl;
        } else {
            return endpoint;
        }
    }
}
