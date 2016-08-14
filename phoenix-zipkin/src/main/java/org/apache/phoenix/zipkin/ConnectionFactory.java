/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.zipkin;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;


/**
*
* ConnectionFactory is to handle database connection
*
*/
public class ConnectionFactory {


  private static String PHOENIX_HOST;
  private static String PHOENIX_PORT;
  
  public static void setHost(String host){
    PHOENIX_HOST = host;
  }
  
  public static void setPort(String port){
    PHOENIX_PORT = port;
  }
  private static Connection con;

  public static Connection getConnection() throws SQLException, ClassNotFoundException {
    if (con == null || con.isClosed()) {
      Class.forName("org.apache.phoenix.jdbc.PhoenixDriver");
      con = DriverManager.getConnection("jdbc:phoenix:"+PHOENIX_HOST+":"+PHOENIX_PORT);
    }
    return con;
  }
}

