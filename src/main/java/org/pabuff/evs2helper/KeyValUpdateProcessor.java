package org.pabuff.evs2helper;

import org.pabuff.dto.ItemIdTypeEnum;
import org.pabuff.dto.ItemTypeEnum;
import org.pabuff.dto.SvcClaimDto;
import org.pabuff.evs2helper.cache.DataAgent;
import org.pabuff.evs2helper.device.DeviceLcStatusHelper;
import org.pabuff.evs2helper.email.SystemNotifier;
import org.pabuff.evs2helper.event.OpResultEvent;
import org.pabuff.evs2helper.event.OpResultPublisher;
import org.pabuff.evs2helper.locale.LocalHelper;
import org.pabuff.evs2helper.report.ReportHelper;
import org.pabuff.evs2helper.scope.ScopeHelper;
import org.pabuff.oqghelper.OqgHelper;
import org.pabuff.oqghelper.QueryHelper;
import org.pabuff.utils.SqlUtil;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.logging.Logger;

@Service
//@Getter <- this will cause lombok to generate 'Source code does not match the bytecode' error
public class KeyValUpdateProcessor {
    private static final Logger logger = Logger.getLogger(KeyValUpdateProcessor.class.getName());

    @Autowired
    OqgHelper oqgHelper;
    @Autowired
    private QueryHelper queryHelper;
    @Autowired
    OpResultPublisher opResultPublisher;
    @Autowired
    private DataAgent dataAgent;
    @Autowired
    SystemNotifier systemNotifier;
    @Autowired
    ScopeHelper scopeHelper;
    @Autowired
    private ReportHelper reportHelper;
    @Autowired
    private LocalHelper localHelper;
    @Autowired
    private DeviceLcStatusHelper deviceLcStatusHelper;

    public Map<String, Object> getOpList(String tableName, String keyName, List<String> meterSns) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String meterSn : meterSns) {
            String sql = "SELECT meter_sn, " + keyName +
                    " from " + tableName + " WHERE meter_sn = '" + meterSn + "'";
            try {
                List<Map<String, Object>> resp = oqgHelper.OqgR(sql);
                if(resp.isEmpty()){
                    continue;
                }
                result.add(resp.getFirst());
            } catch (Exception e) {
                logger.info("Error while getting" + keyName + " for meter: " + meterSn);
                systemNotifier.sendException("ORE Alert", KeyValUpdateProcessor.class.getName(), e.getMessage());
            }
        }
        return Map.of("conc_list", result);
    }

    // update single key/val pair for items from opList
    public Map<String, Object> doOpSingleKeyValUpdate(
            String opName, String scopeStr,
            Map<String, Object> request,
            List<Map<String, Object>> opList,
            SvcClaimDto svcClaimDto,
            boolean isScheduledJobMode, boolean isMock) {

        String meterTypeStr = (String) request.get("item_type");
        ItemTypeEnum itemTypeEnum = ItemTypeEnum.METER;
        if(meterTypeStr != null) {
            itemTypeEnum = ItemTypeEnum.valueOf(meterTypeStr.toUpperCase());
        }
//        Map<String, Object> scopeConfig = scopeHelper.getItemTypeConfig(scopeStr, "");
//
//        ItemTypeEnum itemTypeEnum = ItemTypeEnum.valueOf((String) scopeConfig.get("itemTypeEnum"));

//        String opName = (String) request.get("op_name");
        String keyName = (String) request.get("key_name");

        String itemTableName = "meter";
        String itemSnKey = "meter_sn";
        String itemNameKey = "meter_displayname";
        switch (itemTypeEnum) {
            case METER-> {
                itemTableName = "meter";
                itemSnKey = "meter_sn";
                itemNameKey = "meter_displayname";
            }
            case METER_3P-> {
                itemTableName = "meter_3p";
                itemSnKey = "meter_sn";
                itemNameKey = "meter_id";
            }
            case METER_IWOW-> {
                itemTableName = "meter_iwow";
                itemSnKey = "item_sn";
                itemNameKey = "item_name";
            }
            case TENANT -> {
                itemTableName = "tenant";
                itemSnKey = "tenant_name";//"tenant_label";
                itemNameKey = "tenant_label";//"tenant_name";
            }
            case USER -> {
                itemTableName = "evs2_user";
                itemSnKey = "id";
                itemNameKey = "username";
            }
            case CONCENTRATOR -> {
                itemTableName = "concentrator";
//                itemSnKey = "concentrator_sn";
                itemNameKey = "concentrator_id";
            }
            case CONCENTRATOR_TARIFF -> {
                itemTableName = "concentrator_tariff";
                itemSnKey = "concentrator_id";
//                itemNameKey = "tariff_price";
            }
            case TARIFF_PACKAGE -> {
                itemTableName = "tariff_package";
                itemSnKey = "id";
                itemNameKey = "name";
            }
            case JOB_TYPE -> {
                itemTableName = "job_type";
                itemSnKey = "id";
                itemNameKey = "name";
            }
            case JOB -> {
                itemTableName = "job";
                itemSnKey = "id";
                itemNameKey = "name";
            }
            case JOB_TYPE_SUB -> {
                itemTableName = "job_sub";
                itemSnKey = "id";
//                itemNameKey = "name";
            }
            default -> {
                return Map.of("error", "item_type not supported");
            }
        }

        String itemIdColName = itemSnKey;
        if(opName.contains("replacement")) {
            itemIdColName = itemNameKey;
        }

//        String itemIdTypeStr = (String) request.get("item_id_type");
//        ItemIdTypeEnum itemIdTypeEnum = null;
//        if(itemIdTypeStr != null && !itemIdTypeStr.isBlank()) {
//            itemIdTypeEnum = ItemIdTypeEnum.valueOf(itemIdTypeStr.toUpperCase());
//        }
        String localNowStr = localHelper.getLocalNowStr();
        Map<String, Object> meter0 = //opList.get(0);
                opList.stream()
                        .filter(item -> item.get("checked") != null && (boolean) item.get("checked"))
                        .findFirst()
                        .orElseThrow();
        String meterId0 = (String) (meter0.get(itemSnKey)==null? "null" : meter0.get(itemSnKey));

        queryHelper.postOpLog2(
                localNowStr,
                svcClaimDto.getUserId(),
                svcClaimDto.getUsername(),
                svcClaimDto.getScope(),
                svcClaimDto.getTarget(),
                svcClaimDto.getOperation(),
                meterId0,
                opName,
                opList.size(),
                localNowStr,
                "",
                null);

        for( Map<String, Object> item : opList) {
            if(item.get("error") != null) {
                continue;
            }
            if(item.get("status") != null && ((String)item.get("status")).contains("error")) {
                continue;
            }
            if(item.get("checked") == null || !(boolean) item.get("checked")) {
                continue;
            }

            String displayOpName = opName;
            if(opName.contains(".")) {
                displayOpName = opName.split("\\.")[1];
            }
            String op = item.get("op") == null ? displayOpName : (String) item.get("op");

            String itemSn = (String) item.get(itemSnKey);
            String itemIdValue = itemSn;

            if(itemTypeEnum == ItemTypeEnum.USER) {
                itemIdColName = itemNameKey;
                itemIdValue = (String) item.get(itemNameKey);
            }

            if(opName.contains("replacement")) {
                String meterDisplayname = (String) item.get(itemNameKey);
                if (meterDisplayname == null || meterDisplayname.isBlank()) {
                    logger.info("Error while doing " + opName + " op for item: " + meterDisplayname);
                    continue;
                }
                itemIdValue = meterDisplayname;
            }

            if(itemIdValue == null || itemIdValue.isBlank()) {
                String itemName = (String) item.get(itemNameKey);
                if(itemName == null || itemName.isBlank()) {
                    logger.info("Error while doing " + opName + " op for item: " + itemName);
                    continue;
                }
//                itemSn = meterInfo.get(itemName);
//                Map<String, Object> meterSnResult = dataAgent.getMeterSnFromDisplayname(itemName);
//                itemSn = (String) meterSnResult.get(itemSnKey);

                //query db instead of cache
                if(itemTypeEnum == ItemTypeEnum.METER) {
                    itemSn = queryHelper.getMeterSnFromMeterDisplayname(itemName);

                    if (itemSn == null || itemSn.isBlank()) {
                        logger.info("Error while doing " + opName + " op for item: " + itemName);
                        item.put("error", Map.of("status", "Meter not found"));
                        item.put("prev_status", item.get("status"));
                        item.put("status", op + " error");
                        continue;
                    }
                }
            }

            String newTargetKey = "new_" + keyName;

            logger.info("Doing " + opName + " op for item: " + itemSn);
//            boolean mock = false;
            if(isMock){
                //mock
                try {
                    Thread.sleep(800);
                    if(item.get("meter_displayname")!=null) {
                        if (item.get("meter_displayname").equals("10013014")) {
                            throw new Exception("Meter displayname error");
                        }
                    }
                    item.put("prev_status", item.get("status"));
                    item.put("status", op + " success");
                    item.put(newTargetKey, item.get(keyName));
                }catch (Exception e){
                    item.put("error", Map.of("status", e.getMessage()));
                    item.put("prev_status", item.get("status"));
                    item.put("status", op + " error");
                    item.put("checked", false);
                }
            }else {
                //live
                localNowStr = localHelper.getLocalNowStr();

                String val = (String) item.get(keyName);
                //if val is a number, do not quote it
                // adding '' is handled by SqlUtil.makeUpdateSql
//                if(val.matches("-?\\d+(\\.\\d+)?")){
//                    val = val;
//                }else{
//                    val = "'" + val + "'";
//                }

                //if is device
                if(itemTypeEnum == ItemTypeEnum.METER
                || itemTypeEnum == ItemTypeEnum.METER_3P
                || itemTypeEnum == ItemTypeEnum.METER_IWOW){
                    if("lc_status".equals(keyName)){
                        val = deviceLcStatusHelper.getDeviceLcStatusDbStr(val);
                        if(val == null){
                            logger.info("Error while doing " + opName + " op for item: " + itemSn);
                            item.put("error", Map.of("status", "Invalid lc_status"));
                            item.put("prev_status", item.get("status"));
                            item.put("status", op + " error");
                            item.put("checked", false);
                            continue;
                        }
                    }
                }

                Map<String, Object> content = new HashMap<>();
                content.put(keyName, val);

                if(opName.contains("replacement")) {
                    content.put("commissioned_timestamp", localNowStr);
                }

                if(opName.contains("setsite")){
                    String siteScope = (String) item.get(keyName);
                    siteScope = siteScope.toLowerCase();
                    Map<String, Object> resultProj = queryHelper.getProjectScopeFromSiteScope(siteScope);
                    if(resultProj.containsKey("project_scope")){
                        String projectScope = (String) resultProj.get("project_scope");
                        content.put("scope_str", projectScope);

                        //update user as well
                        if(itemTypeEnum == ItemTypeEnum.METER){
                            String meterDisplayname = (String) item.get(itemNameKey);
                            if(meterDisplayname == null || meterDisplayname.isBlank()) {
                                meterDisplayname = queryHelper.getMeterDisplaynameFromSn(itemSn);
                            }
                            if(meterDisplayname.isBlank()) {
                                logger.info("Error while doing " + opName + " op for item: " + itemSn);
                                item.put("error", Map.of("status", "Meter displayname not found"));
                                item.put("prev_status", item.get("status"));
                                item.put("status", op + " error");
                                item.put("checked", false);
                                continue;
                            }

                            Map<String, Object> result = queryHelper.setUserScope(meterDisplayname, projectScope);
                            if(result.containsKey("error")) {
                                logger.info("Error while doing " + opName + " op for item: " + itemSn);
                                item.put("error", Map.of("status", result.get("error")));
                                item.put("prev_status", item.get("status"));
                                item.put("status", op + " error");
                                item.put("checked", false);
                                continue;
                            }
                        }
                    }else {
                        logger.info("Error while doing " + opName + " op for item: " + itemSn);
                        item.put("error", Map.of("status", "Project scope not found"));
                        item.put("prev_status", item.get("status"));
                        item.put("status", op + " error");
                        item.put("checked", false);
                        continue;
                    }
                }
//                String sql = "UPDATE " + tableName + " SET " + keyName + " = " + val +
//                        " WHERE meter_sn = '" + itemSn + "'";

                Map<String, String> sqlResult = SqlUtil.makeUpdateSql(
                        Map.of(
                                "table", itemTableName,
                                "target_key", itemIdColName,
                                "target_value", itemIdValue,
                                "content", content));
                String sql = sqlResult.get("sql");
                try {
                    Map<String, Object> resp;
                    resp = oqgHelper.OqgIU(sql);
                    if (resp.containsKey("error")) {
                        logger.info("Error while doing " + op + " for item: " + itemSn);
                        item.put("error", Map.of("status", resp.get("error")));
                        item.put("prev_status", item.get("status"));
                        item.put("status", op + " error");
                        item.put("checked", false);
//                            continue;
                    }else{
                        item.put("prev_status", item.get("status"));
                        item.put("status", op + " success");
//                        long concId = queryHelper.getConcentratorIdFromMeterSn(itemSn);
                        List<Map<String, Object>> newValResp;
                        String newValSql = "SELECT " + keyName + " from " + itemTableName +
                                " WHERE " + itemIdColName + " = '" + itemIdValue + "'";
                        try {
                            newValResp = oqgHelper.OqgR(newValSql);
                            String newVal = (String) newValResp.getFirst().get(keyName);
                            item.put(newTargetKey, newVal);
                        }catch (Exception e) {
                            logger.info("Error while getting new " + keyName + " for item: " + itemSn);
                            item.put("error", Map.of("status", e.getMessage()));
                            item.put("prev_status", item.get("status"));
                            item.put("status", op + " error");
                            item.put("checked", false);
                        }
                    }
                } catch (Exception ex) {
                    logger.info("Error while doing " + op + " for item: " + itemSn);
                    item.put("error", Map.of("status", ex.getMessage()));
                    item.put("prev_status", item.get("status"));
                    item.put("status", op + " error");
                    item.put("checked", false);
//                        continue;
                }

            }
            if(!isScheduledJobMode) {
                opResultPublisher.publishEvent(OpResultEvent.builder()
                        .updatedBatchList(opList)
                        .meterOp(/*"do_op_" + */opName)
                        .build());
            }
        }

        if(isScheduledJobMode) {
            List<LinkedHashMap<String, Object>> report = new ArrayList<>();
            for (Map<String, Object> item : opList) {
                LinkedHashMap<String, Object> rec = new LinkedHashMap<>();
                for (String key : item.keySet()) {
                    rec.put(key, item.get(key));
                }
                report.add(rec);
            }

            LinkedHashMap<String, Integer> headerMap = new LinkedHashMap<>();
            for (String key : opList.getFirst().keySet()) {
                headerMap.put(key, 5000);
            }

            Map<String, Object> result = reportHelper.genReportExcel(opName, report, headerMap, "result");
            return result;
        }else{
            return Map.of("list_op_result", opList);
        }
    }

    //update multiple key/val pairs for items from opList
    public Map<String, Object> doOpMultiKeyValUpdate(Map<String, Object> request,
                                                     List<Map<String, Object>> opList, SvcClaimDto svcClaimDto) {

        String meterTypeStr = (String) request.get("item_type");
        ItemTypeEnum itemTypeEnum = ItemTypeEnum.METER;
        if(meterTypeStr != null) {
            itemTypeEnum = ItemTypeEnum.valueOf(meterTypeStr.toUpperCase());
        }

        String itemTableName = "meter";
        String itemSnKey = "meter_sn";
        String itemNameKey = "meter_displayname";
        switch (itemTypeEnum) {
            case METER-> {
                itemTableName = "meter";
                itemSnKey = "meter_sn";
                itemNameKey = "meter_displayname";
            }
            case METER_3P-> {
                itemTableName = "meter_3p";
                itemSnKey = "meter_sn";
                itemNameKey = "meter_id";
            }
            case METER_IWOW-> {
                itemTableName = "meter_iwow";
                itemSnKey = "item_sn";
                itemNameKey = "item_name";
            }
            case TENANT -> {
                itemTableName = "tenant";
                itemSnKey = "tenant_name";//"tenant_label";
                itemNameKey = "tenant_label";//"tenant_name";
            }
            case USER -> {
                itemTableName = "evs2_user";
                itemSnKey = "id";
                itemNameKey = "username";
            }
            case METER_GROUP -> {
                itemTableName = "meter_group";
                itemSnKey = "id";
                itemNameKey = "name";
            }
            case TARIFF_PACKAGE -> {
                itemTableName = "tariff_package";
                itemSnKey = "id";
                itemNameKey = "name";
            }
            case JOB_TYPE -> {
                itemTableName = "job_type";
                itemSnKey = "id";
                itemNameKey = "name";
            }
            case JOB -> {
                itemTableName = "job";
                itemSnKey = "id";
                itemNameKey = "name";
            }
            case JOB_TYPE_SUB -> {
                itemTableName = "job_sub";
                itemSnKey = "id";
//                itemNameKey = "name";
            }
            case BILLING_REC -> {
                itemTableName = "billing_rec_cw";
                itemSnKey = "id";
                itemNameKey = "name";
            }
            case CONCENTRATOR -> {
                itemTableName = "concentrator";
                itemSnKey = "id";
                itemNameKey = "id";
            }
            default -> {
                return Map.of("error", "item_type not supported");
            }
        }

        String opName = (String) request.get("op_name");

        String itemIdTypeStr = (String) request.get("item_id_type");
        ItemIdTypeEnum itemIdTypeEnum = null;
        if(itemIdTypeStr != null && !itemIdTypeStr.isBlank()) {
            itemIdTypeEnum = ItemIdTypeEnum.valueOf(itemIdTypeStr.toUpperCase());
        }

        String displayOpName = opName;
        if(opName.contains(".")) {
            displayOpName = opName.split("\\.")[1];
        }

        String localNowStr = localHelper.getLocalNowStr();
        Map<String, Object> meter0 = //opList.get(0);
                opList.stream()
                        .filter(item -> item.get("checked") != null && (boolean) item.get("checked"))
                        .findFirst()
                        .orElseThrow();
        String meterId0 = (String) (meter0.get(itemSnKey) == null ? "null" : meter0.get(itemSnKey));
        queryHelper.postOpLog2(
                localNowStr,
                svcClaimDto.getUserId(),
                svcClaimDto.getUsername(),
                svcClaimDto.getScope(),
                svcClaimDto.getTarget(),
                svcClaimDto.getOperation(),
                meterId0,
                opName,
                opList.size(),
                localNowStr,
                "",
                null);

        for( Map<String, Object> item : opList) {
            if(item.get("error") != null) {
                continue;
            }
            if(item.get("status") != null && ((String)item.get("status")).contains("error")) {
                continue;
            }
            if(item.get("checked") == null || !(boolean) item.get("checked")) {
                continue;
            }

            String itemSn = (String) item.get(itemSnKey);
            String itemName = (String) item.get(itemNameKey);

            String targetKey = itemSnKey;
            String targetValue = itemSn;

            String op = item.get("op") == null ? displayOpName : (String) item.get("op");

            if(itemIdTypeEnum == null) {
                if (((itemName == null || itemName.isBlank()) || (itemSn == null || itemSn.isBlank()))) {
                    logger.info("Error while doing " + opName + " op for item");
                    item.put("error", Map.of("status", "item_id_type not provided"));
                    continue;
                }
            }else {
                if(itemIdTypeEnum == ItemIdTypeEnum.SN) {
                    if ((itemSn == null || itemSn.isBlank())) {
                        logger.info("Error while doing " + opName + " op for item");
                        item.put("error", Map.of("status", "item_sn not provided"));
                        continue;
                    }
                }else if(itemIdTypeEnum == ItemIdTypeEnum.NAME) {
                    if ((itemName == null || itemName.isBlank())) {
                        logger.info("Error while doing " + opName + " op for item");
                        item.put("error", Map.of("status", "item_name not provided"));
                        continue;
                    }
                    targetKey = itemNameKey;
                    targetValue = itemName;
                }else if(itemIdTypeEnum == ItemIdTypeEnum.ID) {
                    targetKey = "id";
                    targetValue = (String) item.get("id");
                }
            }

            logger.info("Doing " + opName + " for item: " + itemSn);
            boolean mock = false;
            if(mock){
                //mock
                try {
                    Thread.sleep(800);
                    if(item.get(itemNameKey)!=null){
                        if(item.get(itemNameKey).equals("10013014")) {
                            throw new Exception("Meter displayname error");
                        }
                    }
                    item.put("prev_status", item.get("status"));
                    item.put("status", op + " success");
//                    item.put(newTargetKey, targetValue);
                }catch (Exception e){
                    item.put("error", Map.of("status", e.getMessage()));
                    item.put("prev_status", item.get("status"));
                    item.put("status", opName + " error");
                    item.put("checked", false);
                }
            }else {
                //live
//                String localNowStr = DateTimeUtil.getSgNowStr();
                localNowStr = localHelper.getLocalNowStr();

                //sort thru the item map for key and non-empty value pairs to update
                Map<String, Object> content = new HashMap<>();

                String errorText = "";
                for(Map.Entry<String, Object> entry : item.entrySet()) {
                    String key = entry.getKey();
                    Object val = entry.getValue();
                    if(itemTypeEnum == ItemTypeEnum.METER_IWOW){
                        if (key.equals("item_name")){
                            continue;
                        }

                        // paired meter
                        if("paired_meter_name".equals(key)) {
                            if(val == null /*|| val.toString().isBlank()*/) {
                                continue;
                            }

                            // item name first char must be "E"
                            if (!itemName.startsWith("E")) {
                                logger.info("Error while doing " + opName + " op for item: " + itemSn);
                                errorText = "Iwow item_name must start with 'E'";
                                continue;
                            }
                            // key can only start with "R"
                            if (val == null || !val.toString().startsWith("R")) {
                                logger.info("Error while doing " + opName + " op for item: " + itemSn);
                                errorText = "Paired meter_id must start with 'R'";
                                continue;
                            }
                            // get meter id from paired meter name
                            String sql = "SELECT id from meter_iwow WHERE item_name = '" + val + "'";
                            List<Map<String, Object>> resp;
                            try {
                                resp = oqgHelper.OqgR(sql);
                                if (resp.isEmpty()) {
                                    logger.info("Error while doing " + opName + " op for item: " + itemSn);
                                    errorText = "Paired meter not found";
                                    continue;
                                }
                                val = resp.getFirst().get("id");
                            } catch (Exception e) {
                                logger.info("Error while doing " + opName + " op for item: " + itemSn);
                                errorText = "Error getting paired meter";
                                continue;
                            }
//                            content.put("paired_meter_id", val);
                            key = "paired_meter_id";
                        }

                    }else if(itemTypeEnum == ItemTypeEnum.TENANT){
                        if (key.equals("tenant_name")){
                            continue;
                        }
                        if(key.equals("tenant_label") && (val == null /*|| val.toString().isBlank()*/)){
                            continue;
                        }
                        if(key.equals("type")){
                            String typeVal = (String) val;
                            String tenantName = (String) item.get("tenant_name");
                            String newTenantName = tenantName;
                            switch (typeVal) {
                                case "cw_nus_external" -> {
                                    if (tenantName.contains("-int-")) {
                                        newTenantName = tenantName.replace("-int-", "-ext-");
                                    } else if (tenantName.contains("-rd-")) {
                                        newTenantName = tenantName.replace("-rd-", "-ext-");
                                    } else if (tenantName.contains("-v-")) {
                                        newTenantName = tenantName.replace("-v-", "-ext-");
                                    }
                                }
                                case "cw_nus_internal" -> {
                                    if (tenantName.contains("-ext-")) {
                                        newTenantName = tenantName.replace("-ext-", "-int-");
                                    } else if (tenantName.contains("-rd-")) {
                                        newTenantName = tenantName.replace("-rd-", "-int-");
                                    } else if (tenantName.contains("-v-")) {
                                        newTenantName = tenantName.replace("-v-", "-int-");
                                    }
                                }
                                case "cw_nus_rd" -> {
                                    if (tenantName.contains("-int-")) {
                                        newTenantName = tenantName.replace("-int-", "-rd-");
                                    } else if (tenantName.contains("-ext-")) {
                                        newTenantName = tenantName.replace("-ext-", "-rd-");
                                    } else if (tenantName.contains("-v-")) {
                                        newTenantName = tenantName.replace("-v-", "-rd-");
                                    }
                                }
                                case "cw_nus_virtual" -> {
                                    if (tenantName.contains("-int-")) {
                                        newTenantName = tenantName.replace("-int-", "-v-");
                                    } else if (tenantName.contains("-ext-")) {
                                        newTenantName = tenantName.replace("-ext-", "-v-");
                                    } else if (tenantName.contains("-rd-")) {
                                        newTenantName = tenantName.replace("-rd-", "-v-");
                                    }
                                }
                            }
                            content.put("tenant_name", newTenantName);
                        }
                    }else if(itemTypeEnum == ItemTypeEnum.JOB_TYPE_SUB){
//                        content.put("updated_timestamp", localNowStr);
                    }else if(itemTypeEnum == ItemTypeEnum.USER){
                        // id (itemSnKey) is not updatable
                        // username (itemNameKey) is updatable
                        if (key.equals(itemSnKey)) {
                            continue;
                        }
                    }else if(itemTypeEnum == ItemTypeEnum.CONCENTRATOR) {
                        if (key.equals("id")) {
                            continue;
                        }
                    }else if(opName.equals("replacement")) {
                        if (key.equals(itemNameKey)) {
                            continue;
                        }
                    }else{
                        if (key.equals(itemSnKey) || key.equals(itemNameKey)) {
                            continue;
                        }
                    }
                    if (key.equals("error") || key.equals("checked") || key.equals("status") || key.equals("prev_status")) {
                        continue;
                    }
                    if (val == null /*|| val.toString().isBlank()*/) {
                        continue;
                    }

                    //if is device
                    if(itemTypeEnum == ItemTypeEnum.METER
                    || itemTypeEnum == ItemTypeEnum.METER_3P
                    || itemTypeEnum == ItemTypeEnum.METER_IWOW
                    || itemTypeEnum == ItemTypeEnum.CONCENTRATOR){
                        if("lc_status".equals(key)){
                            val = deviceLcStatusHelper.getDeviceLcStatusDbStr(val.toString());
                            if(val == null){
                                logger.info("Error while doing " + opName + " op for item: " + itemSn);
                                item.put("error", Map.of("status", "Invalid lc_status"));
                                item.put("prev_status", item.get("status"));
                                item.put("status", op + " error");
                                item.put("checked", false);
                                continue;
                            }
                        }
                    }

                    content.put(key, val);

                    if(opName.equals("replacement")) {
                        content.put("commissioned_timestamp", localNowStr);
                    }

                    if(itemTypeEnum == ItemTypeEnum.BILLING_REC){
                        if("lc_status".equals(key)){
                            if("mfd".equals(val)) {
                                content.put("mark_delete_timestamp", localNowStr);
                            }else{
                                content.put("mark_delete_timestamp", null);
                            }
                        }
                    }
                }

                if(!errorText.isBlank()){
                    item.put("error", Map.of("status", errorText));
                    item.put("prev_status", item.get("status"));
                    item.put("status", op + " error");
                    item.put("checked", false);
                    continue;
                }

                if(content.isEmpty()){
                    logger.info("Error while doing " + opName + " op for item: " + itemSn);
                    item.put("error", Map.of("status", "No content to update"));
                    item.put("prev_status", item.get("status"));
                    item.put("status", op + " error");
                    item.put("checked", false);
                    continue;
                }

                //all item tables should have updated_timestamp column
                content.put("updated_timestamp", localNowStr);

                Map<String, String> sqlResult = SqlUtil.makeUpdateSql(
                                                Map.of(
                                                "table", itemTableName,
                                                "target_key", targetKey,
                                                "target_value", targetValue,
                                                "content", content));

                String sql = sqlResult.get("sql");
                try {
                    Map<String, Object> resp;
                    resp = oqgHelper.OqgIU(sql);
                    if (resp.containsKey("error")) {
                        logger.info("Error while doing " + op + " for item: " + itemSn);
                        item.put("error", Map.of("status", resp.get("error")));
                        item.put("prev_status", item.get("status"));
                        item.put("status", op + " error");
                        item.put("checked", false);
//                            continue;
                    }else{
                        item.put("prev_status", item.get("status"));
                        item.put("status", op + " success");
                    }
                } catch (Exception ex) {
                    logger.info("Error while doing " + op + " for item: " + itemSn);
                    item.put("error", Map.of("status", ex.getMessage()));
                    item.put("prev_status", item.get("status"));
                    item.put("status", op + " error");
                    item.put("checked", false);
//                        continue;
                }
            }
            opResultPublisher.publishEvent(OpResultEvent.builder()
                    .updatedBatchList(opList)
                    .meterOp(/*"do_op_" + */opName)
                    .build());
        }
        return Map.of("list_op_result", opList);
    }
}
