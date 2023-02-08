package com.future.xlink.request;

import android.content.Context;
import android.text.TextUtils;

import com.elvishew.xlog.XLog;
import com.future.xlink.R;
import com.future.xlink.bean.base.BaseData;
import com.future.xlink.bean.InitParams;
import com.future.xlink.bean.base.MsgData;
import com.future.xlink.bean.other.Agents;
import com.future.xlink.callback.ICmdType;
import com.future.xlink.callback.IHttpRequest;
import com.future.xlink.callback.IMqttCallback;
import com.future.xlink.callback.IPutType;
import com.future.xlink.utils.FileTools;
import com.future.xlink.request.retrofit.IApis;
import com.future.xlink.utils.GsonTools;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import com.future.xlink.utils.NetTools;
import com.future.xlink.utils.TheadTools;
import com.future.xlink.utils.Utils;

public class XLink {
    private MqttAndroidClient client;
    private MqttConnectOptions conOpt;
    private IMqttCallback iMqttCallback;
    private String mqttSsid;
    private String clientId;
    private String[] pingList;
    private static XLink sInstance;
    private CopyOnWriteArrayList<MsgData> pushMap = new CopyOnWriteArrayList<>();
    private boolean isRunPush = false;
    private PushThread pushThread;

    /**
     * 单例模式
     */
    private static XLink instance() {
        if (sInstance == null) {
            synchronized (XLink.class) {
                if (sInstance == null) {
                    sInstance = new XLink();
                }
            }
        }
        return sInstance;
    }

    private XLink() {
        pushThread = new PushThread();
    }

    /**
     * 设置消息回调
     */
    void setiMqtt(IMqttCallback iMqttCallback) {
        instance().iMqttCallback = iMqttCallback;
    }

    static IMqttCallback getiMqtt() {
        return instance().iMqttCallback;
    }

    /**
     * Mqtt执行连接的线程
     */
    static class ConnThread extends Thread {
        Context mCxt;
        InitParams iParams;

        public ConnThread(Context mCxt, InitParams iRparams) {
            this.mCxt = mCxt;
            this.iParams = iRparams;
        }

        @Override
        public void run() {
            createConnect(mCxt, iParams);
        }
    }

    /**
     * 推送消息线程
     */
    static class PushThread extends Thread {
        @Override
        public void run() {
            startPushData();
        }
    }

    /**
     * Mqtt消息处理 连接 消息下发
     */
    static class MqttContentHandle implements MqttCallbackExtended {
        Context mContext;

        public MqttContentHandle(Context mContext) {
            this.mContext = mContext;
        }

        /**
         * 连接完成
         */
        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            instance().isRunPush = true;
            XLog.d("connectComplete: reconnect >> " + reconnect);
            if(getiMqtt()!=null){
                getiMqtt().connState(true, reconnect ? "reConnect complete" : "connect complete");
            }
            TheadTools.execute(instance().pushThread);
        }

        /**
         * 连接丢失
         */
        @Override
        public void connectionLost(Throwable cause) {
            instance().isRunPush = false;
            boolean isPing = NetTools.checkNet(instance().pingList, mContext);
            XLog.e("connectionLost: isPing >> " + isPing);
            if(getiMqtt()!=null){
                getiMqtt().connState(false, cause == null ? (isPing ? "other exception" : "network is lost！") : cause.getMessage());
            }
        }

        /**
         * 服务器消息到达
         */
        @Override
        public void messageArrived(String topic, MqttMessage message) throws Exception {
            XLog.d("messageArrived: message data >> " + message.toString());
            instance().platformHandle(message.toString());
        }


        /**
         * 消息交付完成
         */
        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            BaseData baseData = null;
            try {
                baseData = DataTransfer.deliveryHandle(instance().clientId, token.getMessage());
                XLog.d("deliveryComplete: message=" + token.getMessage().toString());
                getiMqtt().pushed(baseData);
            } catch (Throwable e) {
                getiMqtt().pushFail(baseData, e.getMessage());
            }
        }
    }

    /**
     * 消息处理
     */
    private void platformHandle(String msgJson) throws JSONException {
        JSONObject jHandle = new JSONObject(msgJson);
        String act = jHandle.getString("act");
        String iid = jHandle.getString("iid");
        switch (act) {
            case ICmdType.PLATFORM_EVENT:
            case ICmdType.PLATFORM_UPLOAD:
            case ICmdType.PLATFORM_CMD:
                //服务器回复本机发送的数据
                getiMqtt().iotReplyed(act, iid);
                break;
            default:
                //服务器请求设备中的相关数据
                BaseData baseData = DataTransfer.IotRequest(instance().clientId, jHandle, iid);
                getiMqtt().msgArrives(baseData);
                break;
        }
        //标识已处理过的任务
        for (int i = 0; i < pushMap.size(); i++) {
            MsgData msgData = instance().pushMap.get(i);
            if (msgData.iid.equals(iid)) {
                msgData.isDeliveryed = true;
                XLog.d("platformHandle >> iid >> " + msgData.iid + " , isDeliveryed >> " + msgData.isDeliveryed + ", operation >> " + msgData.operation);
                break;
            }
        }
    }


    /**
     * start push data
     */
    static void startPushData() {
        XLog.d("startPushData start!!!! ThreadName >> " + Thread.currentThread().getName());
        while (instance().isRunPush) {
            for (int i = 0; i < instance().pushMap.size(); i++) {
                MsgData msgData = instance().pushMap.get(i);
                //mqtt连接正常的时候才推送
                XLog.d("msgData >>> " + msgData + ", pushMap.size =" + instance().pushMap.size() + ",isPush >> " + msgData.isPush);
                if (!msgData.isPush) {
                    //处理本地队列未推送的数据
                    pushData(msgData, true);
                } else {
                    if (msgData.isDeliveryed) {
                        //已送达 平台已告知接收完成
                        instance().pushMap.remove(i);
                        XLog.d("platformHandle: pushMap1.remove iid=" + msgData.iid + ",nowSize=" + instance().pushMap.size());
                    } else {
                        //未送达 得到现在的时间减去之前记录的时间
                        long nowSecond = System.currentTimeMillis() / 1000;
                        long beforeSecond = msgData.pushTime;
                        //比较之前的时间与现在的时间相隔是否超过一分钟
                        if (msgData.pushCount < 3) {
                            if (nowSecond - beforeSecond > 1 * 60) {
                                XLog.e("record pos >> [" + msgData.pushCount + "], pushData >> " + msgData.iid + ",operation >> " + msgData.operation);
                                //得到超时的秒数
                                pushData(msgData, false);
                            }
                        } else {
                            instance().pushMap.remove(i);
                            XLog.d("platformHandle: pushMap2.remove iid=" + msgData.iid + ",nowSize=" + instance().pushMap.size());
                            getiMqtt().pushFail(msgData, "Sending data timed out");
                        }
                    }
                }
            }
        }
        XLog.d("startPushData end!!!!");
    }

    /**
     * push data
     *
     * @param msgData content
     * @param isFirst is first push?
     */
    private static void pushData(MsgData msgData, boolean isFirst) {
        if (getConnectStatus()) {
            try {
                String pushData = DataTransfer.getPushData(instance().clientId, msgData);
                XLog.d("pushData >> " + pushData);
                String topic = DataTransfer.getDiffTopic(pushData, instance().clientId, instance().mqttSsid);
                MqttMessage message = new MqttMessage(pushData.getBytes());
                message.setQos(2);
                instance().client.publish(topic, message);
                if (isFirst) {
                    msgData.isPush = true;
                    //The time to save push messages is used for record timeout management
                    msgData.pushTime = System.currentTimeMillis();
                } else {
                    msgData.pushCount++;
                }
                Thread.sleep(150);
            } catch (Throwable e) {
                getiMqtt().pushFail(msgData, e.getMessage());
            }
        }
    }

    /**
     * create connect
     *
     * @param params connect params
     */
    private synchronized static void createConnect(Context mCtx, InitParams params) {
        try {
            XLog.d("createConnect ThreadName >> " + Thread.currentThread().getName());
            instance().mqttSsid = params.mqttSsid;
            String tmpDir = System.getProperty("java.io.tmpdir");
            instance().conOpt = DataTransfer.getConOption(params);
            instance().client = new MqttAndroidClient(mCtx, params.mqttBroker, params.clientId, new MqttDefaultFilePersistence(tmpDir));
            instance().client.setCallback(new MqttContentHandle(mCtx));
            instance().client.connect(instance().conOpt).waitForCompletion();
            XLog.d("createConnect Waiting for connection to finish！");
        } catch (MqttException e) {
            XLog.d("createConnect errMeg >> ", e);
            switch (e.getReasonCode()) {
                case MqttException.REASON_CODE_CLIENT_CONNECTED:
                    getiMqtt().connState(true, params.mqttBroker);
                    break;
                case MqttException.REASON_CODE_NOT_AUTHORIZED:
                    String configFolder = IApis.ROOT + mCtx.getPackageName() + "/" + params.clientId + "/";
                    FileTools.delProperties(configFolder + IApis.MY_PROPERTIES);
                    getiMqtt().connState(false, "REASON_CODE_NOT_AUTHORIZED code = 5");
                    break;
                default:
                    getiMqtt().connState(false, e.getMessage());
                    break;
            }
        }
    }


    /**
     * get connect status
     */
    public static boolean getConnectStatus() {
        return instance().client != null && instance().client.isConnected();
    }

    /**
     * add cmd
     *
     * @param iPutType  唯一码
     * @param operation 操作名称
     * @param dataMap   content
     */
    public static boolean putCmd(@IPutType int iPutType, String iid, String operation, Map<String, Object> dataMap) {
        BaseData baseData = new BaseData(iPutType, iid, operation, dataMap);
        if (getConnectStatus()) {
            instance().pushMap.add(new MsgData(baseData.iPutType, baseData.iid, baseData.operation, baseData.maps, false, false));
            return true;
        }
        return false;
    }

    /**
     * subscribe message
     */
    public static void subscribe(String topicName, int qos) {
        try {
            if (getConnectStatus()) {
                XLog.d("subscribe " + "Subscribing to topic \"" + topicName + "\" qos " + qos);
                instance().client.subscribe(topicName, qos);
                getiMqtt().subscribed(topicName);
            } else {
                getiMqtt().subscribeFail(topicName, "mqtt disconnect");
            }
        } catch (MqttException e) {
            XLog.e("e.getReasonCode() >> " + e.getReasonCode() + ", errMeg:" + e.getMessage());
            getiMqtt().subscribeFail(topicName, "ReasonCode >> " + e.getReasonCode() + ", errMeg >> " + e.getMessage());
        }
    }

    /**
     * read local config and connect mqtt
     */
    public static void connect(Context mCxt, InitParams params, IMqttCallback iMqttCallback) {
        if (!getConnectStatus()) {//判断未连接时进行连接操作
            String configFolder = IApis.ROOT + mCxt.getPackageName() + "/" + params.clientId + "/";
            FileTools.initLogFile(configFolder);
            instance().setiMqtt(iMqttCallback);
            InitParams iRparams = null;
            try {
                iRparams = GsonTools.fromJson(FileTools.readFileData(configFolder + IApis.MY_PROPERTIES), InitParams.class);
            } catch (Throwable e) {
                XLog.e("connect1 >> " + e.getMessage());
            }
            if (iRparams != null && iRparams.checkMqttNotNull()) {
                instance().clientId = iRparams.clientId;
                instance().pingList = new String[]{Utils.patternIp(iRparams.mqttBroker)};
                TheadTools.execute(new ConnThread(mCxt, iRparams));
            } else {
                instance().clientId = params.clientId;
                XLinkHttp.getAgentList(params, new IHttpRequest() {
                    @Override
                    public void requestComplete(String jsonData) {
                        XLog.d("requestComplete: jsonData >> " + jsonData);
                        if (!TextUtils.isEmpty(jsonData)) {
                            try {
                                Agents agents = GsonTools.fromJson(jsonData, Agents.class);
                                if (agents.servers.size() > 0) {
                                    instance().pingList = new String[agents.servers.size()];
                                    for (int i = 0; i < agents.servers.size(); i++) {
                                        String getUrl = Utils.patternIp(agents.servers.get(i));
                                        instance().pingList[i] = getUrl;
                                    }
                                    String bestUrl = agents.servers.get(0);
                                    if (agents.servers.size() > 1) {
                                        bestUrl = Utils.compare(agents.servers);
                                    }
                                    XLog.d("requestComplete: bestUrl >> " + bestUrl);
                                    XLinkHttp.registerDev(params, bestUrl, new IHttpRequest() {
                                        @Override
                                        public void requestComplete(String jsonData) {
                                            boolean isWrite = FileTools.saveConfig(params, configFolder, jsonData);
                                            XLog.d("isWrite=" + isWrite);
                                            if (isWrite) {
                                                createConnect(mCxt, params);
                                            } else {
                                                getiMqtt().connState(false, mCxt.getString(R.string.credential_save_err));
                                            }
                                        }

                                        @Override
                                        public void requestErr(String description) {
                                            getiMqtt().connState(false, description);
                                        }
                                    });
                                } else {
                                    getiMqtt().connState(false, "Agent List size <= 0");
                                }
                            } catch (Throwable e) {
                                XLog.e("connect2 >> " + e.getMessage());
                                getiMqtt().connState(false, "get Agent list failed!");
                            }
                        }
                    }

                    @Override
                    public void requestErr(String description) {
                        XLog.d("requestErr: description >> " + description);
                        getiMqtt().connState(false, description);
                    }
                });
            }
        }
    }

    /**
     * 解除注册
     * @param mContext 上下文
     */
    public static void unInit(Context mContext){
        FileTools.delProperties(IApis.ROOT + mContext.getPackageName() + "/" + instance().clientId + "/"+IApis.MY_PROPERTIES);
        instance().pushMap.clear();
        disConnect();
    }

    /**
     * 断开并重置连接
     */
    public static void disConnect() {
        if(getiMqtt()!=null){
            getiMqtt().connState(false, "Active disconnect");
        }
        instance().iMqttCallback = null;
        if (instance().client != null) {
            instance().client.setCallback(null);
            XLog.d("release the mqtt connection");
            try {
                instance().client.disconnect();
            } catch (Throwable e) {
            }
            try {
                instance().client.close();
            } catch (Throwable e) {
            }
            instance().client = null;
        }
        instance().isRunPush = false;
    }
}
