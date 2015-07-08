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

package com.aliyun.odps.lot.operators;

import com.aliyun.odps.lot.common.ArgumentNullException;
import com.aliyun.odps.lot.common.ScalarExpression;

import apsara.odps.lot.FilterProtos;
import apsara.odps.lot.Lot;

public class Filter extends Operator {

  private ScalarExpression condition;

  public ScalarExpression getCondition() {
    return condition;
  }

  public Filter(ScalarExpression condition) {
    if (condition == null) {
      throw new ArgumentNullException("condition");

    }
    this.condition = condition;
  }

  @Override
  public Lot.LogicalOperator toProtoBuf() {
    assert (getParents().size() == 1);

    Lot.LogicalOperator.Builder builder = Lot.LogicalOperator.newBuilder();
    FilterProtos.Filter.Builder f = FilterProtos.Filter.newBuilder();
    f.setId(getId());
    f.setParentId(getParents().get(0).getId());
    f.setCondition(condition.toProtoBuf());
    builder.setFilter(f.build());

    return builder.build();
  }
}
