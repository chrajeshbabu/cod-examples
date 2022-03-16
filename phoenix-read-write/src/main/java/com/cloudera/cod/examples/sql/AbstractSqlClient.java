/*
 * Copyright 2021 Cloudera, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.cloudera.cod.examples.sql;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;
import java.util.Date;

public abstract class AbstractSqlClient {

  public void dropIfExists(String tableName, Statement stmt) throws SQLException {
    System.out.println("Dropping " + tableName);
    stmt.execute("DROP TABLE IF EXISTS " + tableName);
  }
  
  public void createTable(String tableName, Statement stmt, boolean ifNotExists) throws SQLException {
      System.out.println("Creating " + tableName);
      stmt.execute("CREATE TABLE " + (ifNotExists ? "IF NOT EXISTS " : "") + tableName + 
          " (pk integer not null primary key, data varchar)");
  }

  public int countRows(String tableName, Statement stmt) throws SQLException {
    try (ResultSet results = stmt.executeQuery("SELECT COUNT(1) FROM " + tableName)) {
      if (!results.next()) {
        throw new RuntimeException("Query should have results");
      }
      return results.getInt(1);
    }
  }

  public void run(String tableName, Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      dropIfExists(tableName, stmt);
  
      createTable(tableName, stmt, false);
      conn.setAutoCommit(false);
      try (PreparedStatement pstmt = conn.prepareStatement("UPSERT INTO " + tableName + " values(?,?)")) {
        System.out.println("Writing to " + tableName);
        for (int i = 0; i < 100; i++) {
          pstmt.setInt(1, i);
          pstmt.setString(2, Integer.toString(i));
          pstmt.executeUpdate();
        }
        conn.commit();
      }
  
      System.out.println("Found " + countRows(tableName, stmt) + " records from " + tableName);
    }
  }

  public void runWrites(String tableName, Connection conn, int numRecords) throws SQLException {
    final boolean prevAutoCommit = conn.getAutoCommit();
    try (Statement stmt = conn.createStatement();
        PreparedStatement pstmt = conn.prepareStatement("UPSERT INTO " + tableName + " values(?,?)")) {
      createTable(tableName, stmt, true);

      conn.setAutoCommit(false);
      int batchSize = 500;
      long start = System.nanoTime();
      for (int i = 0; i < numRecords; i++) {
        pstmt.setInt(1, i);
        pstmt.setString(2, Integer.toString(i));
        pstmt.addBatch();
        if (i % 500 == 0) {
          System.out.println(new Date() + " Flushing batched records");
          pstmt.executeBatch();
          conn.commit();
        }
      }
      pstmt.executeBatch();
      conn.commit();
      long end = System.nanoTime();
      long durationInMillis = TimeUnit.MILLISECONDS.convert(end - start, TimeUnit.NANOSECONDS);
      System.out.println("Wrote " + numRecords + " records in " + durationInMillis + "ms");

      System.out.println("Read " + countRows(tableName, stmt) + " records from " + tableName);
    } finally {
      conn.setAutoCommit(prevAutoCommit);
    }
  }
}
