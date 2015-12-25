/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.aliyun.odps.datahub;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import com.aliyun.odps.commons.proto.ProtobufRecordStreamReader;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.map.ObjectMapper;

import com.aliyun.odps.TableSchema;
import com.aliyun.odps.OdpsException;
import com.aliyun.odps.commons.transport.Connection;
import com.aliyun.odps.commons.transport.Response;
import com.aliyun.odps.commons.util.IOUtils;
import com.aliyun.odps.commons.util.JacksonParser;
import com.aliyun.odps.data.Record;
import com.aliyun.odps.rest.RestClient;
import com.aliyun.odps.commons.proto.XstreamPack.XStreamPack;


public class PackReader {

  private RestClient datahubServiceClient;
  private TableSchema tableSchema;
  private String path;
  private MessageDigest messageDigest;
  private Map<String, String> params;
  private Map<String, String> headers;
  private String currPackId;
  private String nextPackId;
  private PackType.ReadMode readMode;
  private ProtobufRecordStreamReader protobufRecordStreamReader;

  public PackReader(RestClient datahubServiceClient, TableSchema tableSchema, String path,
      Map<String, String> params, Map<String, String> headers) {
    this(datahubServiceClient, tableSchema, path, params, headers, PackType.FIRST_PACK_ID);
  }

  public PackReader(RestClient datahubServiceClient, TableSchema tableSchema, String path,
      Map<String, String> params, Map<String, String> headers, String packId) {
    this.datahubServiceClient = datahubServiceClient;
    this.tableSchema = tableSchema;
    this.path = path;
    this.params = params;
    this.headers = headers;
    this.currPackId = null;
    this.nextPackId = null;

    try {
      this.messageDigest = MessageDigest.getInstance("MD5");
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException(e.getMessage());
    }

    this.protobufRecordStreamReader = null;
    seek(packId, PackType.ReadMode.SEEK_CUR);
  }

  private boolean isValid(String pid) {
    return pid != null && !pid.equals(PackType.FIRST_PACK_ID)
      && !pid.equals(PackType.LAST_PACK_ID);
  }

  private void seek(String rpid, PackType.ReadMode mode) {
    if ((rpid == null || rpid.equals("")) && mode != PackType.ReadMode.SEEK_BEGIN
      && mode != PackType.ReadMode.SEEK_END) {
      throw new IllegalArgumentException("Invalid pack id.");
    }

    if (mode == PackType.ReadMode.SEEK_NEXT && isValid(currPackId)
      && currPackId.equals(rpid) && isValid(nextPackId)) {
      rpid = nextPackId;
      mode = PackType.ReadMode.SEEK_CUR;
    }
    else {
      currPackId = null;
    }

    switch (mode) {
      case SEEK_BEGIN:
        nextPackId = PackType.FIRST_PACK_ID;
        break;
      case SEEK_END:
        nextPackId = PackType.LAST_PACK_ID;
        break;
      case SEEK_CUR:
      case SEEK_NEXT:
        nextPackId = rpid;
        break;
      default:
        throw new IllegalArgumentException("Invalid pack read mode.");
    }

    readMode = mode;
    protobufRecordStreamReader = null;
  }

  public SeekPackResult seek(long timeStamp) throws OdpsException, IOException {
    HashMap<String, String> params = new HashMap<String, String>(this.params);
    HashMap<String, String> headers = new HashMap<String, String>(this.headers);

    try {
      params.put(DatahubConstants.SEEK_TIME, Long.toString(timeStamp));
      Connection conn = datahubServiceClient.connect(path, "GET", params, headers);
      Response resp = conn.getResponse();

      if (!resp.isOK()) {
        DatahubException ex = new DatahubException(conn.getInputStream());
        ex.setRequestId(resp.getHeader(DatahubHttpHeaders.HEADER_ODPS_REQUEST_ID));
        throw ex;
      } else {
        byte[] bytes = IOUtils.readFully(conn.getInputStream());
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        ObjectMapper mapper = JacksonParser.getObjectMapper();
        JsonNode tree = mapper.readTree(in);
        JsonNode node = tree.get("PackId");
        if (node != null && !node.isNull()) {
          SeekPackResult startPack = new SeekPackResult(node.asText());
          return startPack;
        } else {
          throw new DatahubException("get pack id fail");
        }
      }
    } catch (DatahubException e) {
      throw e;
    } catch (Exception e) {
      throw new DatahubException(e.getMessage(), e);
    }
  }

  public ReadPackResult read() throws OdpsException, IOException {
    this.protobufRecordStreamReader = null;

    HashMap<String, String> params = new HashMap<String, String>(this.params);
    HashMap<String, String> headers = new HashMap<String, String>(this.headers);

    try {
      String strMode;
      if (readMode == PackType.ReadMode.SEEK_NEXT) {
        strMode = DatahubConstants.ITER_MODE_AFTER_PACKID;
      } else {
        strMode = DatahubConstants.ITER_MODE_AT_PACKID;
      }

      params.put(DatahubConstants.PACK_ID, this.nextPackId);
      params.put(DatahubConstants.ITERATE_MODE, strMode);
      params.put(DatahubConstants.PACK_NUM, "1");

      Connection conn = datahubServiceClient.connect(path, "GET", params, headers);
      Response resp = conn.getResponse();

      if (!resp.isOK()) {
        DatahubException ex = new DatahubException(conn.getInputStream());
        ex.setRequestId(resp.getHeader(DatahubHttpHeaders.HEADER_ODPS_REQUEST_ID));
        throw ex;
      }

      String num = resp.getHeader(DatahubHttpHeaders.HEADER_ODPS_PACK_NUM);
      if (num.equals("0")) {
        return null;
      }

      InputStream in = conn.getInputStream();
      byte[] bytes = IOUtils.readFully(in);
      
      XStreamPack pack = XStreamPack.parseFrom(bytes);
      bytes = pack.getPackData().toByteArray();

      this.protobufRecordStreamReader = new ProtobufRecordStreamReader(
        tableSchema, new ByteArrayInputStream(bytes));

      String npid = resp.getHeader(DatahubHttpHeaders.HEADER_ODPS_NEXT_PACKID);
      String cpid = resp.getHeader(DatahubHttpHeaders.HEADER_ODPS_CURRENT_PACKID);
      long timeStamp = new Long(resp.getHeader(DatahubHttpHeaders.HEADER_ODPS_PACK_TIMESTAMP));
      
      if (!npid.equals(PackType.LAST_PACK_ID)) {
        nextPackId = npid;
        readMode = PackType.ReadMode.SEEK_CUR;
        currPackId = cpid;
      } else {
        nextPackId = cpid;
        readMode = PackType.ReadMode.SEEK_NEXT;
        currPackId = null;
      }
        
      List<Record> records = new ArrayList<Record>();
      Record r = null;
      while ((r = protobufRecordStreamReader.read()) != null)
      {
        records.add(r);
      }

      return new ReadPackResult(cpid, npid, timeStamp, records, pack.hasPackMeta() ? pack.getPackMeta().toByteArray() : null);

    } catch (DatahubException e) {
      throw e;
    } catch (Exception e) {
      throw new DatahubException(e.getMessage(), e);
    }
  }

  public ReadPackResult read(String packId, PackType.ReadMode mode)
    throws OdpsException, IOException {
    seek(packId, mode);
    return read();
  }

  private String generatorMD5(byte[] bytes) {
    byte[] digest = messageDigest.digest(bytes);
    StringBuilder sb = new StringBuilder();
    for (byte b : digest) {
      sb.append(String.format("%02X", b));
    }
    return sb.toString();
  }
}
