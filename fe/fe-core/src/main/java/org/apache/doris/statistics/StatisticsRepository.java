// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.statistics;

import org.apache.doris.analysis.AlterColumnStatsStmt;
import org.apache.doris.analysis.TableName;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.Partition;
import org.apache.doris.common.AnalysisException;
import org.apache.doris.common.DdlException;
import org.apache.doris.common.FeConstants;
import org.apache.doris.statistics.util.DBObjects;
import org.apache.doris.statistics.util.InternalQueryResult.ResultRow;
import org.apache.doris.statistics.util.StatisticsUtil;
import org.apache.doris.system.SystemInfoService;

import org.apache.commons.text.StringSubstitutor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * All the logic that interacts with internal statistics table should be placed here.
 */
public class StatisticsRepository {

    private static final Logger LOG = LogManager.getLogger(StatisticsRepository.class);

    private static final String FULL_QUALIFIED_DB_NAME = "`" + SystemInfoService.DEFAULT_CLUSTER + ":"
            + FeConstants.INTERNAL_DB_NAME + "`";

    private static final String FULL_QUALIFIED_COLUMN_STATISTICS_NAME = FULL_QUALIFIED_DB_NAME + "."
            + "`" + StatisticConstants.STATISTIC_TBL_NAME + "`";

    private static final String FULL_QUALIFIED_COLUMN_HISTOGRAM_NAME = FULL_QUALIFIED_DB_NAME + "."
            + "`" + StatisticConstants.HISTOGRAM_TBL_NAME + "`";

    private static final String FULL_QUALIFIED_ANALYSIS_JOB_TABLE_NAME = FULL_QUALIFIED_DB_NAME + "."
            + "`" + StatisticConstants.ANALYSIS_JOB_TABLE + "`";

    private static final String FETCH_COLUMN_STATISTIC_TEMPLATE = "SELECT * FROM "
            + FULL_QUALIFIED_COLUMN_STATISTICS_NAME
            + " WHERE `id` = '${id}'";

    private static final String FETCH_PARTITIONS_STATISTIC_TEMPLATE = "SELECT * FROM "
            + FULL_QUALIFIED_COLUMN_STATISTICS_NAME
            + " WHERE `id` IN (${idList})";

    private static final String FETCH_COLUMN_HISTOGRAM_TEMPLATE = "SELECT * FROM "
            + FULL_QUALIFIED_COLUMN_HISTOGRAM_NAME
            + " WHERE `id` = '${id}'";

    private static final String PERSIST_ANALYSIS_TASK_SQL_TEMPLATE = "INSERT INTO "
            + FULL_QUALIFIED_ANALYSIS_JOB_TABLE_NAME + " VALUES(${jobId}, ${taskId}, '${catalogName}', '${dbName}',"
            + "'${tblName}','${colName}', '${indexId}','${jobType}', '${analysisType}', "
            + "'${message}', '${lastExecTimeInMs}',"
            + "'${state}', '${scheduleType}')";

    private static final String INSERT_INTO_COLUMN_STATISTICS = "INSERT INTO "
            + FULL_QUALIFIED_COLUMN_STATISTICS_NAME + " VALUES('${id}', ${catalogId}, ${dbId}, ${tblId}, '${idxId}',"
            + "'${colId}', ${partId}, ${count}, ${ndv}, ${nullCount}, '${min}', '${max}', ${dataSize}, NOW())";

    private static final String DROP_TABLE_STATISTICS_TEMPLATE = "DELETE FROM " + FeConstants.INTERNAL_DB_NAME
            + "." + "${tblName}" + " WHERE ${condition}";

    private static final String FETCH_RECENT_STATS_UPDATED_COL =
            "SELECT * FROM "
                    + FeConstants.INTERNAL_DB_NAME + "." + StatisticConstants.STATISTIC_TBL_NAME
                    + " WHERE part_id is NULL "
                    + " ORDER BY update_time DESC LIMIT "
                    + StatisticConstants.STATISTICS_RECORDS_CACHE_SIZE;

    private static final String FETCH_STATS_FULL_NAME =
            "SELECT id, catalog_id, db_id, tbl_id, idx_id, col_id, part_id FROM "
                    + FeConstants.INTERNAL_DB_NAME + "." + StatisticConstants.STATISTIC_TBL_NAME
                    + " ORDER BY update_time "
                    + "LIMIT ${limit} OFFSET ${offset}";

    public static ColumnStatistic queryColumnStatisticsByName(long tableId, String colName) {
        ResultRow resultRow = queryColumnStatisticById(tableId, colName);
        if (resultRow == null) {
            return ColumnStatistic.UNKNOWN;
        }
        return ColumnStatistic.fromResultRow(resultRow);
    }

    public static List<ColumnStatistic> queryColumnStatisticsByPartitions(TableName tableName, String colName,
            List<String> partitionNames) throws AnalysisException {
        DBObjects dbObjects = StatisticsUtil.convertTableNameToObjects(tableName);
        Set<Long> partitionIds = new HashSet<>();
        for (String partitionName : partitionNames) {
            Partition partition = dbObjects.table.getPartition(partitionName);
            if (partition == null) {
                throw new AnalysisException(String.format("partition:%s not exists", partitionName));
            }
            partitionIds.add(partition.getId());
        }
        return queryPartitionStatistics(dbObjects.table.getId(),
                colName, partitionIds).stream().map(ColumnStatistic::fromResultRow).collect(
                Collectors.toList());
    }

    public static ResultRow queryColumnStatisticById(long tblId, String colName) {
        return queryColumnStatisticById(tblId, colName, false);
    }

    public static ResultRow queryColumnHistogramById(long tblId, String colName) {
        return queryColumnStatisticById(tblId, colName, true);
    }

    private static ResultRow queryColumnStatisticById(long tblId, String colName, boolean isHistogram) {
        Map<String, String> map = new HashMap<>();
        String id = constructId(tblId, -1, colName);
        map.put("id", id);
        List<ResultRow> rows = isHistogram ? StatisticsUtil.executeQuery(FETCH_COLUMN_HISTOGRAM_TEMPLATE, map) :
                StatisticsUtil.executeQuery(FETCH_COLUMN_STATISTIC_TEMPLATE, map);
        int size = rows.size();
        if (size > 1) {
            throw new IllegalStateException(String.format("id: %s should be unique, but return more than one row", id));
        }
        return size == 0 ? null : rows.get(0);
    }

    public static List<ResultRow> queryPartitionStatistics(long tblId, String colName, Set<Long> partIds) {
        StringJoiner sj = new StringJoiner(",");
        for (Long partId : partIds) {
            sj.add("'" + constructId(tblId, -1, colName, partId) + "'");
        }
        Map<String, String> params = new HashMap<>();
        params.put("idList", sj.toString());
        List<ResultRow> rows = StatisticsUtil.executeQuery(FETCH_PARTITIONS_STATISTIC_TEMPLATE, params);
        return rows == null ? Collections.emptyList() : rows;
    }

    public static Histogram queryColumnHistogramByName(long tableId, String colName) {
        ResultRow resultRow = queryColumnHistogramById(tableId, colName);
        if (resultRow == null) {
            return Histogram.UNKNOWN;
        }
        return Histogram.fromResultRow(resultRow);
    }

    private static String constructId(Object... params) {
        StringJoiner stringJoiner = new StringJoiner("-");
        for (Object param : params) {
            stringJoiner.add(param.toString());
        }
        return stringJoiner.toString();
    }

    public static void dropStatistics(long tblId, Set<String> colNames) throws DdlException {
        dropStatistics(tblId, colNames, StatisticConstants.STATISTIC_TBL_NAME);
        dropStatistics(tblId, colNames, StatisticConstants.HISTOGRAM_TBL_NAME);
    }

    public static void dropStatistics(long tblId, Set<String> colNames, String statsTblName) throws DdlException {
        Map<String, String> params = new HashMap<>();
        String right = colNames.stream().map(s -> "'" + s + "'").collect(Collectors.joining(","));
        String inPredicate = String.format("tbl_id = %s AND %s IN (%s)", tblId, "col_id", right);
        params.put("tblName", statsTblName);
        params.put("condition", inPredicate);
        try {
            StatisticsUtil.execUpdate(new StringSubstitutor(params).replace(DROP_TABLE_STATISTICS_TEMPLATE));
        } catch (Exception e) {
            throw new DdlException(e.getMessage(), e);
        }
    }

    private static <T> void buildPredicate(String fieldName, Set<T> fieldValues, StringBuilder predicate) {
        StringJoiner predicateBuilder = new StringJoiner(",", "(", ")");
        fieldValues.stream().map(value -> String.format("'%s'", value))
                .forEach(predicateBuilder::add);
        String partPredicate = String.format(" AND %s IN %s", fieldName, predicateBuilder);
        predicate.append(partPredicate);
    }

    public static void persistAnalysisTask(AnalysisTaskInfo analysisTaskInfo) throws Exception {
        Map<String, String> params = new HashMap<>();
        params.put("jobId", String.valueOf(analysisTaskInfo.jobId));
        params.put("taskId", String.valueOf(analysisTaskInfo.taskId));
        params.put("catalogName", analysisTaskInfo.catalogName);
        params.put("dbName", analysisTaskInfo.dbName);
        params.put("tblName", analysisTaskInfo.tblName);
        params.put("colName", analysisTaskInfo.colName == null ? "" : analysisTaskInfo.colName);
        params.put("indexId", analysisTaskInfo.indexId == null ? "-1" : String.valueOf(analysisTaskInfo.indexId));
        params.put("jobType", analysisTaskInfo.jobType.toString());
        params.put("analysisType", analysisTaskInfo.analysisMethod.toString());
        params.put("message", "");
        params.put("lastExecTimeInMs", "0");
        params.put("state", AnalysisState.PENDING.toString());
        params.put("scheduleType", analysisTaskInfo.scheduleType.toString());
        StatisticsUtil.execUpdate(
                new StringSubstitutor(params).replace(PERSIST_ANALYSIS_TASK_SQL_TEMPLATE));
    }

    public static void alterColumnStatistics(AlterColumnStatsStmt alterColumnStatsStmt) throws Exception {
        TableName tableName = alterColumnStatsStmt.getTableName();
        DBObjects objects = StatisticsUtil.convertTableNameToObjects(tableName);
        String rowCount = alterColumnStatsStmt.getValue(StatsType.ROW_COUNT);
        String ndv = alterColumnStatsStmt.getValue(StatsType.NDV);
        String nullCount = alterColumnStatsStmt.getValue(StatsType.NUM_NULLS);
        String min = alterColumnStatsStmt.getValue(StatsType.MIN_VALUE);
        String max = alterColumnStatsStmt.getValue(StatsType.MAX_VALUE);
        String dataSize = alterColumnStatsStmt.getValue(StatsType.DATA_SIZE);
        ColumnStatisticBuilder builder = new ColumnStatisticBuilder();
        String colName = alterColumnStatsStmt.getColumnName();
        Column column = objects.table.getColumn(colName);
        if (rowCount != null) {
            builder.setCount(Double.parseDouble(rowCount));
        }
        if (ndv != null) {
            Double dNdv = Double.parseDouble(ndv);
            builder.setNdv(dNdv);
            builder.setOriginalNdv(dNdv);
        }
        if (nullCount != null) {
            builder.setNumNulls(Double.parseDouble(nullCount));
        }
        if (min != null) {
            builder.setMinExpr(StatisticsUtil.readableValue(column.getType(), min));
            builder.setMinValue(StatisticsUtil.convertToDouble(column.getType(), min));
        }
        if (max != null) {
            builder.setMaxExpr(StatisticsUtil.readableValue(column.getType(), max));
            builder.setMaxValue(StatisticsUtil.convertToDouble(column.getType(), max));
        }
        if (dataSize != null) {
            builder.setDataSize(Double.parseDouble(dataSize));
        }

        ColumnStatistic columnStatistic = builder.build();
        Map<String, String> params = new HashMap<>();
        params.put("id", constructId(objects.table.getId(), -1, colName));
        params.put("catalogId", String.valueOf(objects.catalog.getId()));
        params.put("dbId", String.valueOf(objects.db.getId()));
        params.put("idxId", "-1");
        params.put("tblId", String.valueOf(objects.table.getId()));
        params.put("colId", String.valueOf(colName));
        params.put("partId", "NULL");
        params.put("count", String.valueOf(columnStatistic.count));
        params.put("ndv", String.valueOf(columnStatistic.ndv));
        params.put("nullCount", String.valueOf(columnStatistic.numNulls));
        params.put("min", min == null ? "NULL" : min);
        params.put("max", max == null ? "NULL" : max);
        params.put("dataSize", String.valueOf(columnStatistic.dataSize));
        StatisticsUtil.execUpdate(INSERT_INTO_COLUMN_STATISTICS, params);
        Env.getCurrentEnv().getStatisticsCache()
                .updateColStatsCache(objects.table.getId(), -1, colName, builder.build());
    }

    public static List<ResultRow> fetchRecentStatsUpdatedCol() {
        return StatisticsUtil.execStatisticQuery(FETCH_RECENT_STATS_UPDATED_COL);
    }

    public static List<ResultRow> fetchStatsFullName(long limit, long offset) {
        Map<String, String> params = new HashMap<>();
        params.put("limit", String.valueOf(limit));
        params.put("offset", String.valueOf(offset));
        return StatisticsUtil.execStatisticQuery(new StringSubstitutor(params).replace(FETCH_STATS_FULL_NAME));
    }
}
