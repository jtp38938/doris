/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

suite("q22") {
    String db = context.config.getDbNameByFile(new File(context.file.parent))
    sql "use ${db}"
    sql 'set enable_nereids_planner=true'
    sql 'set enable_fallback_to_original_planner=false'
    sql "set runtime_filter_mode='GLOBAL'"
    sql "set enable_runtime_filter_prune=true"
    sql 'set exec_mem_limit=21G'
    sql 'set enable_new_cost_model=false'
    sql 'set global exec_mem_limit = 21G'
    sql 'set global broadcast_row_count_limit = 30000000'
    
    qt_select """
    explain shape plan
        select 
        cntrycode,
        count(*) as numcust,
        sum(c_acctbal) as totacctbal
    from
        (
            select
                substring(c_phone, 1, 2) as cntrycode,
                c_acctbal
            from
                customer
            where
                substring(c_phone, 1, 2) in
                    ('13', '31', '23', '29', '30', '18', '17')
                and c_acctbal > (
                    select
                        avg(c_acctbal)
                    from
                        customer
                    where
                        c_acctbal > 0.00
                        and substring(c_phone, 1, 2) in
                            ('13', '31', '23', '29', '30', '18', '17')
                )
                and not exists (
                    select
                        *
                    from
                        orders
                    where
                        o_custkey = c_custkey
                )
        ) as custsale
    group by
        cntrycode
    order by
        cntrycode;
    """
}
