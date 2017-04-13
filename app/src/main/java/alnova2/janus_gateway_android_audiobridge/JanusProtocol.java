package alnova2.janus_gateway_android_audiobridge;

import android.util.Log;

import org.json.JSONObject;

import java.net.URI;
import java.util.HashMap;

import de.tavendo.autobahn.WebSocket;
import de.tavendo.autobahn.WebSocketConnection;
import de.tavendo.autobahn.WebSocketOptions;

import static android.webkit.ConsoleMessage.MessageLevel.LOG;

/**
 * Created by alnova2 on 2017. 4. 13..
 */

public class JanusProtocol implements WebSocket.WebSocketConnectionObserver {
    private final String TAG="JanusProtocol";
    private JanusProtocolCallback mCallback;
    private WebSocketConnection mConnection=new WebSocketConnection();
    private HashMap<String,String> mTransactions=new HashMap();
    private HashMap<String,String> mAttachTransaction=new HashMap();
    private HashMap<String,Long> mHandleIds=new HashMap();
    private Long mSessionId;
    private Thread mKeepAliveThread;

    public JanusProtocol(JanusProtocolCallback callback){
        mCallback=callback;
    }
    public boolean connect(String uri){
        try{
            WebSocketOptions webSocketOptions = new WebSocketOptions();
            mConnection.connect(new URI(uri),new String[]{"janus-protocol"},this,webSocketOptions);
            return true;
        } catch (Exception e){
            return false;
        }
    }
    public boolean isConnected(){
        return mConnection.isConnected();
    }
    public void disconnect(){
        mConnection.disconnect();
    }
    public boolean destroySession(){
        try{
            JSONObject request=new JSONObject();
            request.put("janus","destroy");
            return sendWSMsg("destroy",request);
        } catch (Exception e) {
            Log.d(TAG, "createSession Error:"+e.getMessage());
            return false;
        }
    }
    public boolean createSession(){
        try{
            JSONObject request=new JSONObject();
            request.put("janus","create");
            return sendWSMsg("create",request);
        } catch (Exception e) {
            Log.d(TAG, "createSession Error:"+e.getMessage());
            return false;
        }
    }
    public boolean attachPlugin(String plugin_name,String opaque_id){
        try{
            JSONObject request=new JSONObject();
            request.put("janus","attach");
            request.put("opaqueId",opaque_id);
            request.put("session_id",mSessionId);
            request.put("plugin",plugin_name);
            return sendWSMsg("attach",request);
        } catch (Exception e){
            Log.d(TAG, "attachPlugin Error:"+e.getMessage());
            return false;
        }
    }
    public boolean sendPluginMsg(String plugin_names,JSONObject body){
        try{
            JSONObject request=new JSONObject();
            request.put("janus","message");
            request.put("session_id",mSessionId);
            request.put("handle_id",mHandleIds.get(plugin_names));
            return sendWSMsg("attach",request);
        } catch (Exception e){
            Log.d(TAG, "attachPlugin Error:"+e.getMessage());
            return false;
        }
    }
    public boolean sendTricle(String plugin_name,String candidate){
        try{
            JSONObject request=new JSONObject();
            request.put("janus","tricle");
            request.put("session_id",mSessionId);
            request.put("handle_id",mHandleIds.get(plugin_name));
            request.put("candidate",candidate);
            return sendWSMsg("tricle",request);
        } catch (Exception e){
            Log.d(TAG, "attachPlugin Error:"+e.getMessage());
            return false;
        }
    }
    public boolean startKeepAlive(){
        mKeepAliveThread=new Thread(new Runnable(){
            @Override
            public void run() {
                while(mKeepAliveThread.isAlive()){
                    try{
                        JSONObject request=new JSONObject();
                        request.put("janus","keepalive");
                        request.put("session_id",mSessionId);
                        sendWSMsg("keepalive",request);
                        Thread.sleep(30000);
                    } catch (Exception e) {
                        Log.d(TAG, "startKeepAlive Error:"+e.getMessage());
                    }
                }
            }
        });
        return true;
    }
    private boolean sendWSMsg(String order,JSONObject msg){
        try{
            String trxId=getTrxId();
            msg.put("transaction",trxId);
            mTransactions.put(trxId,order);
            mConnection.sendTextMessage(msg.toString());
            Log.d(TAG,"[ANDROID>JANUS] MSG:"+msg.toString());
            return true;
        } catch (Exception e){
            Log.d(TAG,"sendWSMsg Error:"+e.getMessage());
            return false;
        }
    }
    private String getTrxId(){
        String charSet="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        String randomString="";
        for(int i=0;i<12;i++){
            int randomPoz=(int)Math.floor(Math.random()*charSet.length());
            randomString=randomString+charSet.substring(randomPoz,randomPoz+1);
        }
        return randomString;
    }
    //WebSocketConnectionObserver Implementation
    @Override
    public void onOpen() {
        mCallback.onOpened(true,null);
    }

    @Override
    public void onClose(WebSocketCloseNotification webSocketCloseNotification, String s) {
        mCallback.onClosed(s);
    }

    @Override
    public void onTextMessage(String s) {
        Log.d(TAG,"[JANUS>ANDROID] MSG:"+s);
        try {
            JSONObject parsed = new JSONObject(s);
            switch(parsed.getString("janus")){
                case "success":
                    switch(mTransactions.get(parsed.getString("transaction"))) {
                        case "create":
                            mSessionId=parsed.getJSONObject("data").getLong("id");
                            mCallback.onCreateSessionReply(true,null);
                            break;
                        case "attach":
                            mHandleIds.put(mAttachTransaction.get(parsed.getString("transaction")),parsed.getJSONObject("data").getLong("id"));
                            mCallback.onAttachPluginReply(true,null);
                            break;
                        case "destroy":
                            break;
                        default:
                    }
                    break;
                case "error":
                    mCallback.onError(mTransactions.get(parsed.getString("transaction")),parsed);
                    break;
                case "event":
                    mCallback.onPluginEvent(parsed);
                    break;
                case "ack":
                    break;
                default:
            }
            if(!parsed.getString("janus").equals("ack"))
                if(!mTransactions.get(parsed.getString("transaction")).equals("message")) mTransactions.remove(parsed.getString("transaction"));
        } catch (Exception e){
            Log.d(TAG,"onTextMessage Error:"+e.getMessage());
        }
    }

    @Override
    public void onRawTextMessage(byte[] bytes) {
        //nothing to do
    }

    @Override
    public void onBinaryMessage(byte[] bytes) {
        //nothing to do
    }
}
