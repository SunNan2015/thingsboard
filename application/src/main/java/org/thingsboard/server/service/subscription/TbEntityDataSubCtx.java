/**
 * Copyright © 2016-2020 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.subscription;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.page.PageData;
import org.thingsboard.server.common.data.query.EntityData;
import org.thingsboard.server.common.data.query.EntityDataQuery;
import org.thingsboard.server.common.data.query.EntityKey;
import org.thingsboard.server.common.data.query.EntityKeyType;
import org.thingsboard.server.common.data.query.TsValue;
import org.thingsboard.server.service.telemetry.TelemetryWebSocketService;
import org.thingsboard.server.service.telemetry.TelemetryWebSocketSessionRef;
import org.thingsboard.server.service.telemetry.cmd.v2.EntityDataCmd;
import org.thingsboard.server.service.telemetry.cmd.v2.EntityDataUpdate;
import org.thingsboard.server.service.telemetry.cmd.v2.LatestValueCmd;
import org.thingsboard.server.service.telemetry.cmd.v2.TimeSeriesCmd;
import org.thingsboard.server.service.telemetry.sub.TelemetrySubscriptionUpdate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
public class TbEntityDataSubCtx extends TbAbstractDataSubCtx<EntityDataQuery> {

    @Getter
    @Setter
    private TimeSeriesCmd tsCmd;
    @Getter
    private PageData<EntityData> data;
    @Getter
    @Setter
    private boolean initialDataSent;
    private TimeSeriesCmd curTsCmd;
    private LatestValueCmd latestValueCmd;

    public TbEntityDataSubCtx(String serviceId, TelemetryWebSocketService wsService,
                              TbLocalSubscriptionService localSubscriptionService,
                              TelemetryWebSocketSessionRef sessionRef, int cmdId) {
        super(serviceId, wsService, localSubscriptionService, sessionRef, cmdId);
    }

    public void setData(PageData<EntityData> data) {
        this.data = data;
    }

    public List<TbSubscription> createSubscriptions(List<EntityKey> keys, boolean resultToLatestValues) {
        this.subToEntityIdMap = new HashMap<>();
        List<TbSubscription> tbSubs = new ArrayList<>();
        Map<EntityKeyType, List<EntityKey>> keysByType = getEntityKeyByTypeMap(keys);
        for (EntityData entityData : data.getData()) {
            tbSubs.addAll(addSubscriptions(entityData, keysByType, resultToLatestValues));
        }
        return tbSubs;
    }

    private Map<EntityKeyType, List<EntityKey>> getEntityKeyByTypeMap(List<EntityKey> keys) {
        Map<EntityKeyType, List<EntityKey>> keysByType = new HashMap<>();
        keys.forEach(key -> keysByType.computeIfAbsent(key.getType(), k -> new ArrayList<>()).add(key));
        return keysByType;
    }

    private List<TbSubscription> addSubscriptions(EntityData entityData, Map<EntityKeyType, List<EntityKey>> keysByType, boolean resultToLatestValues) {
        List<TbSubscription> subscriptionList = new ArrayList<>();
        keysByType.forEach((keysType, keysList) -> {
            int subIdx = sessionRef.getSessionSubIdSeq().incrementAndGet();
            subToEntityIdMap.put(subIdx, entityData.getEntityId());
            switch (keysType) {
                case TIME_SERIES:
                    subscriptionList.add(createTsSub(entityData, subIdx, keysList, resultToLatestValues));
                    break;
                case CLIENT_ATTRIBUTE:
                    subscriptionList.add(createAttrSub(entityData, subIdx, keysType, TbAttributeSubscriptionScope.CLIENT_SCOPE, keysList));
                    break;
                case SHARED_ATTRIBUTE:
                    subscriptionList.add(createAttrSub(entityData, subIdx, keysType, TbAttributeSubscriptionScope.SHARED_SCOPE, keysList));
                    break;
                case SERVER_ATTRIBUTE:
                    subscriptionList.add(createAttrSub(entityData, subIdx, keysType, TbAttributeSubscriptionScope.SERVER_SCOPE, keysList));
                    break;
                case ATTRIBUTE:
                    subscriptionList.add(createAttrSub(entityData, subIdx, keysType, TbAttributeSubscriptionScope.ANY_SCOPE, keysList));
                    break;
            }
        });
        return subscriptionList;
    }

    private TbSubscription createAttrSub(EntityData entityData, int subIdx, EntityKeyType keysType, TbAttributeSubscriptionScope scope, List<EntityKey> subKeys) {
        Map<String, Long> keyStates = buildKeyStats(entityData, keysType, subKeys);
        log.trace("[{}][{}][{}] Creating attributes subscription for [{}] with keys: {}", serviceId, cmdId, subIdx, entityData.getEntityId(), keyStates);
        return TbAttributeSubscription.builder()
                .serviceId(serviceId)
                .sessionId(sessionRef.getSessionId())
                .subscriptionId(subIdx)
                .tenantId(sessionRef.getSecurityCtx().getTenantId())
                .entityId(entityData.getEntityId())
                .updateConsumer((s, subscriptionUpdate) -> sendWsMsg(s, subscriptionUpdate, keysType))
                .allKeys(false)
                .keyStates(keyStates)
                .scope(scope)
                .build();
    }

    private TbSubscription createTsSub(EntityData entityData, int subIdx, List<EntityKey> subKeys, boolean resultToLatestValues) {
        Map<String, Long> keyStates = buildKeyStats(entityData, EntityKeyType.TIME_SERIES, subKeys);
        if (entityData.getTimeseries() != null) {
            entityData.getTimeseries().forEach((k, v) -> {
                long ts = Arrays.stream(v).map(TsValue::getTs).max(Long::compareTo).orElse(0L);
                log.trace("[{}][{}] Updating key: {} with ts: {}", serviceId, cmdId, k, ts);
                keyStates.put(k, ts);
            });
        }
        log.trace("[{}][{}][{}] Creating time-series subscription for [{}] with keys: {}", serviceId, cmdId, subIdx, entityData.getEntityId(), keyStates);
        return TbTimeseriesSubscription.builder()
                .serviceId(serviceId)
                .sessionId(sessionRef.getSessionId())
                .subscriptionId(subIdx)
                .tenantId(sessionRef.getSecurityCtx().getTenantId())
                .entityId(entityData.getEntityId())
                .updateConsumer((sessionId, subscriptionUpdate) -> sendWsMsg(sessionId, subscriptionUpdate, EntityKeyType.TIME_SERIES, resultToLatestValues))
                .allKeys(false)
                .keyStates(keyStates)
                .build();
    }

    private Map<String, Long> buildKeyStats(EntityData entityData, EntityKeyType keysType, List<EntityKey> subKeys) {
        Map<String, Long> keyStates = new HashMap<>();
        subKeys.forEach(key -> keyStates.put(key.getKey(), 0L));
        if (entityData.getLatest() != null) {
            Map<String, TsValue> currentValues = entityData.getLatest().get(keysType);
            if (currentValues != null) {
                currentValues.forEach((k, v) -> {
                    log.trace("[{}][{}] Updating key: {} with ts: {}", serviceId, cmdId, k, v.getTs());
                    keyStates.put(k, v.getTs());
                });
            }
        }
        return keyStates;
    }

    private void sendWsMsg(String sessionId, TelemetrySubscriptionUpdate subscriptionUpdate, EntityKeyType keyType) {
        sendWsMsg(sessionId, subscriptionUpdate, keyType, true);
    }

    private void sendWsMsg(String sessionId, TelemetrySubscriptionUpdate subscriptionUpdate, EntityKeyType keyType, boolean resultToLatestValues) {
        EntityId entityId = subToEntityIdMap.get(subscriptionUpdate.getSubscriptionId());
        if (entityId != null) {
            log.trace("[{}][{}][{}][{}] Received subscription update: {}", sessionId, cmdId, subscriptionUpdate.getSubscriptionId(), keyType, subscriptionUpdate);
            if (resultToLatestValues) {
                sendLatestWsMsg(entityId, sessionId, subscriptionUpdate, keyType);
            } else {
                sendTsWsMsg(entityId, sessionId, subscriptionUpdate, keyType);
            }
        } else {
            log.trace("[{}][{}][{}][{}] Received stale subscription update: {}", sessionId, cmdId, subscriptionUpdate.getSubscriptionId(), keyType, subscriptionUpdate);
        }
    }

    private void sendLatestWsMsg(EntityId entityId, String sessionId, TelemetrySubscriptionUpdate subscriptionUpdate, EntityKeyType keyType) {
        Map<String, TsValue> latestUpdate = new HashMap<>();
        subscriptionUpdate.getData().forEach((k, v) -> {
            Object[] data = (Object[]) v.get(0);
            latestUpdate.put(k, new TsValue((Long) data[0], (String) data[1]));
        });
        EntityData entityData = getDataForEntity(entityId);
        if (entityData != null && entityData.getLatest() != null) {
            Map<String, TsValue> latestCtxValues = entityData.getLatest().get(keyType);
            log.trace("[{}][{}][{}] Going to compare update with {}", sessionId, cmdId, subscriptionUpdate.getSubscriptionId(), latestCtxValues);
            if (latestCtxValues != null) {
                latestCtxValues.forEach((k, v) -> {
                    TsValue update = latestUpdate.get(k);
                    if (update != null) {
                        //Ignore notifications about deleted keys
                        if (!(update.getTs() == 0 && (update.getValue() == null || update.getValue().isEmpty()))) {
                            if (update.getTs() < v.getTs()) {
                                log.trace("[{}][{}][{}] Removed stale update for key: {} and ts: {}", sessionId, cmdId, subscriptionUpdate.getSubscriptionId(), k, update.getTs());
                                latestUpdate.remove(k);
                            } else if ((update.getTs() == v.getTs() && update.getValue().equals(v.getValue()))) {
                                log.trace("[{}][{}][{}] Removed duplicate update for key: {} and ts: {}", sessionId, cmdId, subscriptionUpdate.getSubscriptionId(), k, update.getTs());
                                latestUpdate.remove(k);
                            }
                        } else {
                            log.trace("[{}][{}][{}] Received deleted notification for: {}", sessionId, cmdId, subscriptionUpdate.getSubscriptionId(), k);
                        }
                    }
                });
                //Setting new values
                latestUpdate.forEach(latestCtxValues::put);
            }
        }
        if (!latestUpdate.isEmpty()) {
            Map<EntityKeyType, Map<String, TsValue>> latestMap = Collections.singletonMap(keyType, latestUpdate);
            entityData = new EntityData(entityId, latestMap, null);
            wsService.sendWsMsg(sessionId, new EntityDataUpdate(cmdId, null, Collections.singletonList(entityData)));
        }
    }

    private void sendTsWsMsg(EntityId entityId, String sessionId, TelemetrySubscriptionUpdate subscriptionUpdate, EntityKeyType keyType) {
        Map<String, List<TsValue>> tsUpdate = new HashMap<>();
        subscriptionUpdate.getData().forEach((k, v) -> {
            Object[] data = (Object[]) v.get(0);
            tsUpdate.computeIfAbsent(k, key -> new ArrayList<>()).add(new TsValue((Long) data[0], (String) data[1]));
        });
        EntityData entityData = getDataForEntity(entityId);
        if (entityData != null && entityData.getLatest() != null) {
            Map<String, TsValue> latestCtxValues = entityData.getLatest().get(keyType);
            log.trace("[{}][{}][{}] Going to compare update with {}", sessionId, cmdId, subscriptionUpdate.getSubscriptionId(), latestCtxValues);
            if (latestCtxValues != null) {
                latestCtxValues.forEach((k, v) -> {
                    List<TsValue> updateList = tsUpdate.get(k);
                    if (updateList != null) {
                        for (TsValue update : new ArrayList<>(updateList)) {
                            if (update.getTs() < v.getTs()) {
                                log.trace("[{}][{}][{}] Removed stale update for key: {} and ts: {}", sessionId, cmdId, subscriptionUpdate.getSubscriptionId(), k, update.getTs());
                                updateList.remove(update);
                            } else if ((update.getTs() == v.getTs() && update.getValue().equals(v.getValue()))) {
                                log.trace("[{}][{}][{}] Removed duplicate update for key: {} and ts: {}", sessionId, cmdId, subscriptionUpdate.getSubscriptionId(), k, update.getTs());
                                updateList.remove(update);
                            }
                            if (updateList.isEmpty()) {
                                tsUpdate.remove(k);
                            }
                        }
                    }
                });
                //Setting new values
                tsUpdate.forEach((k, v) -> {
                    Optional<TsValue> maxValue = v.stream().max(Comparator.comparingLong(TsValue::getTs));
                    maxValue.ifPresent(max -> latestCtxValues.put(k, max));
                });
            }
        }
        if (!tsUpdate.isEmpty()) {
            Map<String, TsValue[]> tsMap = new HashMap<>();
            tsUpdate.forEach((key, tsValue) -> tsMap.put(key, tsValue.toArray(new TsValue[tsValue.size()])));
            entityData = new EntityData(entityId, null, tsMap);
            wsService.sendWsMsg(sessionId, new EntityDataUpdate(cmdId, null, Collections.singletonList(entityData)));
        }
    }

    private EntityData getDataForEntity(EntityId entityId) {
        return data.getData().stream().filter(item -> item.getEntityId().equals(entityId)).findFirst().orElse(null);
    }
    
    public TbEntityDataSubCtxUpdateResult update(PageData<EntityData> newData) {
        Map<EntityId, EntityData> oldDataMap;
        if (data != null && !data.getData().isEmpty()) {
            oldDataMap = data.getData().stream().collect(Collectors.toMap(EntityData::getEntityId, Function.identity()));
        } else {
            oldDataMap = Collections.emptyMap();
        }
        Map<EntityId, EntityData> newDataMap = newData.getData().stream().collect(Collectors.toMap(EntityData::getEntityId, Function.identity()));
        if (oldDataMap.size() == newDataMap.size() && oldDataMap.keySet().equals(newDataMap.keySet())) {
            log.trace("[{}][{}] No updates to entity data found", sessionRef.getSessionId(), cmdId);
            return TbEntityDataSubCtxUpdateResult.EMPTY;
        } else {
            this.data = newData;
            List<Integer> subIdsToCancel = new ArrayList<>();
            List<TbSubscription> subsToAdd = new ArrayList<>();
            Set<EntityId> currentSubs = new HashSet<>();
            subToEntityIdMap.forEach((subId, entityId) -> {
                if (!newDataMap.containsKey(entityId)) {
                    subIdsToCancel.add(subId);
                } else {
                    currentSubs.add(entityId);
                }
            });
            log.trace("[{}][{}] Subscriptions that are invalid: {}", sessionRef.getSessionId(), cmdId, subIdsToCancel);
            subIdsToCancel.forEach(subToEntityIdMap::remove);
            List<EntityData> newSubsList = newDataMap.entrySet().stream().filter(entry -> !currentSubs.contains(entry.getKey())).map(Map.Entry::getValue).collect(Collectors.toList());
            if (!newSubsList.isEmpty()) {
                boolean resultToLatestValues;
                List<EntityKey> keys = null;
                if (curTsCmd != null) {
                    resultToLatestValues = false;
                    keys = curTsCmd.getKeys().stream().map(key -> new EntityKey(EntityKeyType.TIME_SERIES, key)).collect(Collectors.toList());
                } else if (latestValueCmd != null) {
                    resultToLatestValues = true;
                    keys = latestValueCmd.getKeys();
                } else {
                    resultToLatestValues = true;
                }
                if (keys != null && !keys.isEmpty()) {
                    Map<EntityKeyType, List<EntityKey>> keysByType = getEntityKeyByTypeMap(keys);
                    newSubsList.forEach(
                            entity -> {
                                log.trace("[{}][{}] Found new subscription for entity: {}", sessionRef.getSessionId(), cmdId, entity.getEntityId());
                                subsToAdd.addAll(addSubscriptions(entity, keysByType, resultToLatestValues));
                            }
                    );
                }
            }
            wsService.sendWsMsg(sessionRef.getSessionId(), new EntityDataUpdate(cmdId, data, null));
            return new TbEntityDataSubCtxUpdateResult(subIdsToCancel, subsToAdd);
        }
    }

    public void setCurrentCmd(EntityDataCmd cmd) {
        curTsCmd = cmd.getTsCmd();
        latestValueCmd = cmd.getLatestCmd();
    }

    @Data
    @AllArgsConstructor
    public static class TbEntityDataSubCtxUpdateResult {

        private static TbEntityDataSubCtxUpdateResult EMPTY = new TbEntityDataSubCtxUpdateResult(Collections.emptyList(), Collections.emptyList());

        private List<Integer> subsToCancel;
        private List<TbSubscription> subsToAdd;
    }
}
