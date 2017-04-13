package alnova2.janus_gateway_android_audiobridge;

import org.json.JSONObject;

/**
 * Created by alnova2 on 2017. 4. 13..
 */

public interface JanusProtocolCallback {
    public void onOpened(boolean isSuccess, Exception ex);
    public void onClosed(String msg);
    public void onCreateSessionReply(boolean isSuccess, Exception ex);
    public void onAttachPluginReply(boolean isSuccess, Exception ex);
    public void onPluginEvent(JSONObject msg);
    public void onError(String order,JSONObject msg);
}
