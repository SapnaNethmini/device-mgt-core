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
package io.entgra.device.mgt.core.device.mgt.core.otp.mgt.dao;

import io.entgra.device.mgt.core.device.mgt.common.DeviceManagementConstants;
import io.entgra.device.mgt.core.device.mgt.common.exceptions.UnsupportedDatabaseEngineException;
import io.entgra.device.mgt.core.device.mgt.core.otp.mgt.dao.impl.GenericOTPManagementDAOImpl;
import io.entgra.device.mgt.core.device.mgt.core.otp.mgt.dao.impl.OracleOTPManagementDAOImpl;
import io.entgra.device.mgt.core.device.mgt.core.otp.mgt.dao.impl.PostgreSQLOTPManagementDAOImpl;
import io.entgra.device.mgt.core.device.mgt.core.otp.mgt.dao.impl.SQLServerOTPManagementDAOImpl;
import io.entgra.device.mgt.core.device.mgt.core.otp.mgt.util.ConnectionManagerUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * This class intends to act as the primary entity that hides all DAO instantiation related complexities and logic so
 * that the business objection handling layer doesn't need to be aware of the same providing seamless plug-ability of
 * different data sources, connection acquisition mechanisms as well as different forms of DAO implementations to the
 * high-level implementations that require Application management related metadata persistence.
 */
public class OTPManagementDAOFactory {

    private static String databaseEngine;
    private static final Log log = LogFactory.getLog(OTPManagementDAOFactory.class);

    public static void init(String datasourceName) {
        ConnectionManagerUtil.resolveDataSource(datasourceName);
        databaseEngine = ConnectionManagerUtil.getDatabaseType();
    }

    public static void init(DataSource dtSource) {
        try (Connection connection = dtSource.getConnection()) {
            databaseEngine = connection.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            log.error("Error occurred while retrieving config.datasource connection", e);
        }
    }

    public static OTPManagementDAO getOTPManagementDAO() {
        if (databaseEngine != null) {
            switch (databaseEngine) {
                case DeviceManagementConstants.DataBaseTypes.DB_TYPE_H2:
                case DeviceManagementConstants.DataBaseTypes.DB_TYPE_MYSQL:
                    return new GenericOTPManagementDAOImpl();
                case DeviceManagementConstants.DataBaseTypes.DB_TYPE_POSTGRESQL:
                    return new PostgreSQLOTPManagementDAOImpl();
                case DeviceManagementConstants.DataBaseTypes.DB_TYPE_MSSQL:
                    return new SQLServerOTPManagementDAOImpl();
                case DeviceManagementConstants.DataBaseTypes.DB_TYPE_ORACLE:
                    return new OracleOTPManagementDAOImpl();
                default:
                    throw new UnsupportedDatabaseEngineException("Unsupported database engine : " + databaseEngine);
            }
        }
        throw new IllegalStateException("Database engine has not initialized properly.");
    }
}
