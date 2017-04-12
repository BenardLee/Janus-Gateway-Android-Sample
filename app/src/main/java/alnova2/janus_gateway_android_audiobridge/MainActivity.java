package alnova2.janus_gateway_android_audiobridge;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.ToggleButton;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.tavendo.autobahn.WebSocket;
import de.tavendo.autobahn.WebSocketConnection;
import de.tavendo.autobahn.WebSocketOptions;

public class MainActivity extends AppCompatActivity {
    private final String TAG="MainActivity";

    //UI Components
    private ToggleButton mConnectButton;
    private Button mStartButton;
    private Button mStopButton;
    private Spinner mCodecSpinner;
    private TextView mAudioBitRateText;
    private Switch mAudioEchoCancelationSwitch;
    private Switch mAudioAutoGainControlSwitch;
    private Switch mAudioHighPassFilterSwitch;
    private Switch mAudioNoiseSupressionSwitch;
    private Switch mAudioLevelControlSwitch;
    private Switch mAudioBitRateSwitch;
    private TextView mAppLogTextView;
    private EditText mRoomIdText;
    private ToggleButton mMicSwitch;
    private ToggleButton mSPKSwitch;
    private String mPreferAudioCodec;

    //private LinkedList<IceCandidate> queuedRemoteCandidates;
    //private LinkedList<IceCandidate> queuedLocalCandidates;

    private ConcurrentLinkedQueue<IceCandidate> queuedRemoteCandidates;
    private ConcurrentLinkedQueue<IceCandidate> queuedLocalCandidates;

    //Audio Configurations
    private WebSocketConnection mConnection=new WebSocketConnection();
    private WebSocketObserver mWSObserver=new WebSocketObserver();
    private final String wsuri="ws://192.168.0.55:8888/janus";
    private String mWSStatus="NOT_CONNECTED";
    private SDPObserver mSDPObserver;
    private PCObserver mPCObserver;
    private boolean mModAudioPrefer=false;

    //WebRTC Related Components
    private AppRTCAudioManager mLocalAudioManager;
    List<PeerConnection.IceServer> mIceServers=new ArrayList<PeerConnection.IceServer>();

    private MediaStream mLocalMediaStream;
    private MediaConstraints mLocalMediaConstrants;
    private MediaConstraints mLocalAudioConstraints;
    private MediaConstraints mPeerConnectionConstraints;
    private PeerConnectionFactory mPeerConnectionFactory;
    private PeerConnection mPeerConnection;

    private AudioSource mLocalAudioSource;
    private AudioTrack mRemoteAudioTrack;
    private AudioTrack mLocalAudioTrack;


    private List<PeerConnection.IceServer> iceServers=new ArrayList<PeerConnection.IceServer>();

    //From AppRetDemo
    private static final String AUDIO_ECHO_CANCELLATION_CONSTRAINT = "googEchoCancellation";
    private static final String AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT = "googAutoGainControl";
    private static final String AUDIO_HIGH_PASS_FILTER_CONSTRAINT = "googHighpassFilter";
    private static final String AUDIO_NOISE_SUPPRESSION_CONSTRAINT = "googNoiseSuppression";
    private static final String AUDIO_LEVEL_CONTROL_CONSTRAINT = "levelControl";
    private static final String DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement";

    private boolean mIsInitiator;

    private HashMap<String,String> mTransactionQueue=new HashMap<String,String>();
    private long mJanusSessionId;
    private long mJanusHandleId;
    private String mOpaqueId;

    private TimerTask mTimerTask;
    private Timer mTimer;
    private boolean mOfferReceived=false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        //1. Setup UI Components
        mConnectButton=(ToggleButton)findViewById(R.id.toggleButton);
        mConnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG,"Button Status:"+mConnectButton.isChecked());
                //Disconnect WebSocket
                if(mConnectButton.isChecked()){
                    connectWS(wsuri);
                } else {
                    if(mWSStatus.equals("CONNECTED")) {
                        disconnectWS();
                        mConnectButton.setChecked(false);
                    }
                }
            }
        });
        mStartButton =(Button)findViewById(R.id.startButton);
        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try{
                    //Step 1. Send Session Create Request
                    if(mWSStatus.equals("CONNECTED")){
                        String requestTransaction=getTrxId();
                        JSONObject request=new JSONObject();
                        request.put("janus","create");
                        request.put("transaction",requestTransaction);
                        mTransactionQueue.put(requestTransaction,"CREATE_SESSION");
                        Log.d(TAG,"[JANUS] M>J Send Create Session Msg:"+request.toString());
                        mConnection.sendTextMessage(request.toString());
                    }
                } catch (Exception e) {
                    Log.d(TAG, "Send Msg Error");
                }
            }
        });
        mStopButton=(Button)findViewById(R.id.stoptButton);

        mStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mWSStatus.equals("CONNECTED")) {
                    JSONObject json=new JSONObject();
                    try {
                        //[Destroy Janus Session]
                        json.put("janus","destroy");
                        json.put("transaction",getTrxId());
                        json.put("session_id",mJanusSessionId);
                        mConnection.sendTextMessage(json.toString());
                        mPeerConnection.close();
                        mPeerConnection=null;
                        //this is for reserving local Ice Candidate before answer received.
                        mOfferReceived=false;
                        disconnectWS();
                        mConnectButton.setChecked(false);
                        stopAudio();
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
        });
        mCodecSpinner=(Spinner)findViewById(R.id.codecSpinner);
        ArrayAdapter codecAdapter=ArrayAdapter.createFromResource(this, R.array.audioCodecs,android.R.layout.simple_spinner_item);
        mCodecSpinner.setAdapter(codecAdapter);
        mCodecSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                CharSequence selected=(CharSequence)mCodecSpinner.getItemAtPosition(position);
                Log.d(TAG,"Prefer Codec:"+selected.toString());
                if(selected.toString().equals("OPUS")==false){
                    mModAudioPrefer=true;
                    mPreferAudioCodec=selected.toString();
                } else mModAudioPrefer=false;
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {

            }
        });
        mAudioEchoCancelationSwitch = (Switch)findViewById(R.id.audioEchoCancelationSwitch);
        mAudioAutoGainControlSwitch = (Switch)findViewById(R.id.audioAutoGainControlSwitch);
        mAudioHighPassFilterSwitch = (Switch)findViewById(R.id.audioHighPassFilterSwitch);
        mAudioNoiseSupressionSwitch = (Switch)findViewById(R.id.audioNoiseSupressionSwitch);
        mAudioLevelControlSwitch = (Switch)findViewById(R.id.audioLevelControlSwitch);
        mAudioBitRateSwitch = (Switch)findViewById(R.id.audioBitRateSwitch);
        mAudioBitRateText=(TextView)findViewById(R.id.audioBitRateText);
        mAppLogTextView=(TextView)findViewById(R.id.AppLog);
        mRoomIdText=(EditText) findViewById(R.id.roomId);
        mRoomIdText.setText("5555");
        mMicSwitch=(ToggleButton)findViewById(R.id.localStreamOff);
        mMicSwitch.setChecked(true); //MIC Default Status = On
        mMicSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG,"MIC Button Status:"+mMicSwitch.isChecked());
                if(mMicSwitch.isChecked()==false){
                    //OFF the MIC
                    Log.d(TAG,"MIC Button Status:"+mMicSwitch.isChecked());
                    if(mLocalAudioTrack!=null) mLocalAudioTrack.setEnabled(false);
                    mMicSwitch.setChecked(false);
                    mAppLogTextView.append("Mic Off.\n");
                } else {
                    //On the MIC
                    if(mLocalAudioTrack!=null) {
                        mLocalAudioTrack.setEnabled(true);
                        if(mLocalAudioTrack.enabled()) {
                            mMicSwitch.setChecked(true);
                            mAppLogTextView.append("Mic On.\n");
                        }

                    }
                }
            }
        });
        mSPKSwitch=(ToggleButton)findViewById(R.id.remoteStreamOff);
        mSPKSwitch.setChecked(true); //SPK Default Status = On
        mSPKSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG,"SPK Button Status:"+mSPKSwitch.isChecked());
                if(mSPKSwitch.isChecked()==false){
                    //OFF the SPK
                    if(mRemoteAudioTrack!=null) mRemoteAudioTrack.setEnabled(false);
                    mSPKSwitch.setChecked(false);
                    mAppLogTextView.append("Speaker Off.\n");
                } else {
                    //On the SPK
                    if(mRemoteAudioTrack!=null) {
                        mRemoteAudioTrack.setEnabled(true);
                        if(mRemoteAudioTrack.enabled()) {
                            mSPKSwitch.setChecked(true);
                            mAppLogTextView.append("Speaker On.\n");
                        }
                    }
                }
            }
        });

        mLocalAudioManager = AppRTCAudioManager.create(getApplicationContext());
        startAudio();

        //Create MediaConstraints
        mLocalAudioConstraints = new MediaConstraints();

        //Set MediaConstraints - Audio Only
        mLocalMediaConstrants = new MediaConstraints();
        mLocalMediaConstrants.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        mLocalMediaConstrants.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"));

        //Create PeerConnection Factory
        Log.d(TAG, "Create PeerConnectionFactory...");
        if (!PeerConnectionFactory.initializeAndroidGlobals(this, true, true, true)) {
            Log.d(TAG, "Failed to initializeAndroidGlobals");
            return;
        }

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        options.networkIgnoreMask = 0;
        mPeerConnectionFactory=new PeerConnectionFactory(options);
    }


    private void createLocalMediaStream(){
        Log.d(TAG,"Create Local MediaStream");
        mLocalMediaStream =mPeerConnectionFactory.createLocalMediaStream("ARDAMS");
        //Create AudioTrack
        mLocalAudioSource =mPeerConnectionFactory.createAudioSource(mLocalAudioConstraints);
        mLocalAudioTrack=mPeerConnectionFactory.createAudioTrack("ARDAMSa0", mLocalAudioSource);
        mLocalAudioTrack.setEnabled(true);
        //Add Local AudioTrack to MediaStream
        mLocalMediaStream.addTrack(mLocalAudioTrack);
    }

    private void startAudio(){
        Log.d(TAG, "Starting the audio manager...");
        mLocalAudioManager.start(new AppRTCAudioManager.AudioManagerEvents() {
            // This method will be called each time the number of available audio
            // devices has changed.
            @Override
            public void onAudioDeviceChanged(
                    AppRTCAudioManager.AudioDevice audioDevice, Set<AppRTCAudioManager.AudioDevice> availableAudioDevices) {
                onAudioManagerDevicesChanged(audioDevice, availableAudioDevices);
            }
        });
    }

    private void stopAudio(){
        mLocalAudioManager.stop();
    }

    private void createPeerConnection(){
        Log.d(TAG, "Create PeerConnection...");
        queuedRemoteCandidates = new ConcurrentLinkedQueue<>();
        queuedLocalCandidates = new ConcurrentLinkedQueue<IceCandidate>();
        //Set Turn Server
        iceServers.add(new PeerConnection.IceServer("turn:172.19.136.204:3478","testusr","test123"));
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
        rtcConfig.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED;
        rtcConfig.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE;
        rtcConfig.continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        rtcConfig.keyType = PeerConnection.KeyType.ECDSA;

        //Create PeerConnection Constraints
        mPeerConnectionConstraints = new MediaConstraints();
        mPeerConnectionConstraints.optional.add(new MediaConstraints.KeyValuePair(DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT, "true"));
        mPCObserver=new PCObserver();
        mPeerConnection= mPeerConnectionFactory.createPeerConnection(rtcConfig,mPeerConnectionConstraints,mPCObserver);
        //Add Local MediaStream to PeerConnection
        mPeerConnection.addStream(mLocalMediaStream);
        mAppLogTextView.append("PeerConnnection Created.\n");
        mIsInitiator = false;
    }
    private void setAudioProcessing(){
        String AudioEchoCancelationSwitch="false";
        String AudioAutoGainControlSwitch="false";
        String AudioHighPassFilterSwitch="false";
        String AudioNoiseSupressionSwitch="false";
        String AudioLevelControlSwitch="false";

        if(mAudioEchoCancelationSwitch.isChecked()) AudioEchoCancelationSwitch="true";
        if(mAudioAutoGainControlSwitch.isChecked()) AudioAutoGainControlSwitch="true";
        if(mAudioHighPassFilterSwitch.isChecked()) AudioHighPassFilterSwitch="true";
        if(mAudioNoiseSupressionSwitch.isChecked()) AudioNoiseSupressionSwitch="true";
        if(mAudioLevelControlSwitch.isChecked()) AudioLevelControlSwitch="true";

        mLocalAudioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_ECHO_CANCELLATION_CONSTRAINT, AudioEchoCancelationSwitch));
        mLocalAudioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_LEVEL_CONTROL_CONSTRAINT, AudioAutoGainControlSwitch));
        mLocalAudioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_NOISE_SUPPRESSION_CONSTRAINT, AudioNoiseSupressionSwitch));
        mLocalAudioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_HIGH_PASS_FILTER_CONSTRAINT, AudioHighPassFilterSwitch));
        mLocalAudioConstraints.mandatory.add(new MediaConstraints.KeyValuePair(AUDIO_AUTO_GAIN_CONTROL_CONSTRAINT, AudioLevelControlSwitch));

        if(mAudioBitRateSwitch.isChecked()){
            Log.d(TAG,"AudioBitRateSwitch is on..Value:"+mAudioBitRateText.toString());
        }

    }

    private boolean connectWS(String uri){
        try{
            WebSocketOptions webSocketOptions = new WebSocketOptions();
            mConnection.connect(new URI(uri),new String[]{"janus-protocol"},mWSObserver,webSocketOptions);
        } catch (Exception e){
            Log.d(TAG,"Exception:connectWS:Conneceting To WS Error:"+e.getMessage());
            return false;
        }
        return true;
    }

    private void disconnectWS(){
        Log.d(TAG, "disconnect Websocket");
        mConnection.disconnect();
        mWSStatus="NOT_CONNECTED";
    }

    private boolean sendWSMsg(JSONObject sendMsgJson){
        if(mWSStatus.equals("CONNECTED")){
            Log.d(TAG,"send Websocket data:"+sendMsgJson.toString());
            mConnection.sendTextMessage(sendMsgJson.toString());
            return true;
        } else return false;
    }

    private class WebSocketObserver implements WebSocket.WebSocketConnectionObserver {
        @Override
        public void onOpen() {
            Log.d(TAG, "WebSocket connection opened to:");
            mWSStatus="CONNECTED";
            mConnectButton.setChecked(true);
        }

        @Override
        public void onClose(WebSocket.WebSocketConnectionObserver.WebSocketCloseNotification code, String reason) {
            Log.d(TAG, "WebSocket connection closed. Code: " + code + ". Reason: " + reason );
        }

        @Override
        public void onTextMessage(String payload) {
            Log.d(TAG, "[JANUS] J > M " + payload);
            try {
                JSONObject json = new JSONObject(payload);
                //Send 한 결과에 대한 Response
                if(json.has("transaction")){
                    String recvTransactionId=json.getString("transaction");
                    String sendTransactionOrder=mTransactionQueue.get(recvTransactionId);
                    boolean trxRemoveFlag=true;
                    if(sendTransactionOrder.equals("CREATE_SESSION")){
                        //Create Session에 대한 Response 처리
                        if(json.getString("janus").equals("success")){
                            JSONObject data=json.getJSONObject("data");
                            //성공 결과로 넘겨지는 id 값이 세션id 임
                            mJanusSessionId=data.getLong("id");
                            JSONObject request=new JSONObject();
                            String attachTransaction=getTrxId();
                            //Step 2. 해당 세션 값을 가지고 audiobridge에 Attach
                            request.put("janus","attach");
                            request.put("transaction",attachTransaction);
                            request.put("opaqueId",mOpaqueId);
                            request.put("session_id",mJanusSessionId);
                            request.put("plugin","janus.plugin.audiobridge");
                            mTransactionQueue.put(attachTransaction,"ATTACH_PLUGIN");
                            mConnection.sendTextMessage(request.toString());
                            Log.d(TAG,"[JANUS] Send Attach audiobridgeplugin Msg:"+request.toString());
                        } else {
                            //실패..
                            Log.d(TAG,"[JANUS] CREATE_SESSION Fail");
                        }
                    } else if(sendTransactionOrder.equals("ATTACH_PLUGIN")){
                        //Attach Plugin에 대한 Response 처리
                        if(json.getString("janus").equals("success")){
                            JSONObject data=json.getJSONObject("data");
                            //성공 결과로 넘겨지는 id값이 핸들 id 임
                            mJanusHandleId=data.getLong("id");
                            //Step 3. 해당 핸들 값을 가지고 대상 Room에 Join
                            JSONObject request=new JSONObject();
                            String joinTransaction=getTrxId();
                            request.put("janus","message");
                            request.put("transaction",joinTransaction);
                            request.put("session_id",mJanusSessionId);
                            request.put("handle_id",mJanusHandleId);
                            JSONObject body=new JSONObject();
                            body.put("request","join");
                            int roomId=Integer.parseInt(mRoomIdText.getText().toString());
                            Log.d(TAG,"Join RoomId:"+roomId);
                            body.put("room",roomId);
                            body.put("display",getTrxId());
                            request.put("body",body);
                            mTransactionQueue.put(joinTransaction,"JOIN_ROOM");
                            mConnection.sendTextMessage(request.toString());
                            Log.d(TAG,"[JANUS] Send Join Msg:"+request.toString());
                        } else {
                            Log.d(TAG,"[JANUS_RECV] ATTACH_PLUGIN Fail");
                            //What to do ?
                        }
                    } else if(sendTransactionOrder.equals("JOIN_ROOM")){
                        if(json.getString("janus").equals("event")){
                            JSONObject plugindata=json.getJSONObject("plugindata");
                            JSONObject data=plugindata.getJSONObject("data");
                            //Step 4. Room에 조인이 성공하면 WebRTC 채널 생성을 시도한다(SDP Offer Send -> SDP Answer Receive, ICE Candidate 전달)
                            if(data.getString("audiobridge").equals("joined")){
                                Log.d(TAG,"[JANUS] JOIN_ROOM Success..CreateUserMedia");
                                //set audio configuration
                                setAudioProcessing();
                                //Start Audio
                                startAudio();
                                //Create LocalMediaStream
                                createLocalMediaStream();
                                //Create PeerConnection
                                createPeerConnection();
                                //Create Offer
                                if (mPeerConnection != null) {
                                    Log.d(TAG, "[WEBRTC] PC Create OFFER");
                                    mIsInitiator=true;
                                    mSDPObserver=new SDPObserver();
                                    //createOffer하게 되면 mSDPObserver에서 SDP를 추출해서 Signal Server로 전송
                                    mPeerConnection.createOffer(mSDPObserver, mLocalMediaConstrants);
                                }
                            } else {
                                Log.d(TAG,"[JANUS] JOIN_ROOM Fail Msg:"+json.toString());
                            }
                        } else if(json.getString("janus").equals("ack")) {
                            Log.d(TAG,"[JANUS] JOIN_ROOM ack received..");
                            trxRemoveFlag=false;
                        } else {
                            Log.d(TAG,"[JANUS] Unknown Msg:"+json.toString());
                            //What to do ?
                        }
                    } else if(sendTransactionOrder.equals("OFFER")){
                        //SDP Offer에 대한 JANUS 서버의 SDP ANswer
                        Log.d(TAG,"[JANUS] OFFER Reply");
                        if(json.getString("janus").equals("ack")) {
                            Log.d(TAG,"[JANUS] OFFER Transported..");
                            trxRemoveFlag=false;
                        } else  if(json.getString("janus").equals("event")) {
                            Log.d(TAG,"[JANUS] Maybe Answer is received..");
                            JSONObject plugindata=json.getJSONObject("plugindata");
                            JSONObject data=plugindata.getJSONObject("data");
                            JSONObject jsep=json.getJSONObject("jsep");
                            if(data.getString("result").equals("ok")){
                                if(jsep!=null && jsep.getString("type").equals("answer")){
                                    Log.d(TAG,"[JANUS] Type Answer..Set Remote Descriptor..sdp:"+jsep.getString("sdp"));
                                    mIsInitiator=false;
                                    SessionDescription sdpAnswer = new SessionDescription(SessionDescription.Type.fromCanonicalForm("answer"), jsep.getString("sdp"));
                                    mPeerConnection.setRemoteDescription(mSDPObserver,sdpAnswer);
                                    mOfferReceived=true;
                                    Log.d(TAG,"[JANUS] Reserved ICE:"+queuedLocalCandidates.size());
                                    while(!queuedLocalCandidates.isEmpty()){
                                        IceCandidate rCandidate=queuedLocalCandidates.remove();
                                        Log.d(TAG,"Send Reserved onIceCandidate event");
                                        JSONObject request=new JSONObject();
                                        String iceCandidateTrx=getTrxId();
                                        try {
                                            request.put("janus", "trickle");
                                            request.put("session_id",mJanusSessionId);
                                            request.put("handle_id",mJanusHandleId);
                                            request.put("transaction",iceCandidateTrx);
                                            JSONObject candidate=new JSONObject();
                                            candidate.put("candidate",rCandidate.sdp);
                                            candidate.put("sdpMid",rCandidate.sdpMid);
                                            candidate.put("sdpMLineIndex",rCandidate.sdpMLineIndex);
                                            request.put("candidate",candidate);
                                            Log.d(TAG, "[JANUS_SEND] Send icecandidate msg :"+request.toString());
                                            mTransactionQueue.put(iceCandidateTrx,"SEND_ICE");
                                            mConnection.sendTextMessage(request.toString());
                                        } catch (Exception e){
                                            Log.d(TAG, "[JANUS_SEND] Exception when sending icecandidate msg :"+e.getMessage());
                                        }
                                    }
                                }
                            }
                        }  else {
                            Log.d(TAG,"[JANUS] Unknown Msg:"+json.toString());
                            //What to do ?
                        }
                    } else if(sendTransactionOrder.equals("SEND_ICE")){
                        Log.d(TAG,"[JANUS] SEND_ICE Transported..");
                    }
                    if(trxRemoveFlag) mTransactionQueue.remove(recvTransactionId);
                } else {

                    String janus=json.getString("janus");

                    if(janus.equals("webrtcup")){
                        //WebRTC is ready.
                        Log.d(TAG, "[JANUS] WebRTC is READY!");
                    } else if(janus.equals("media")){
                        //JANUS가 media를 받는 것에 대한 정보
                        Log.d(TAG, "[JANUS] Media Up Janus is receiving "+json.getString("type")+" enabled:"+json.getBoolean("receiving"));
                    } else if (janus.equals("slowlink")) {
                        //Janus reporting problems sending media to a user (user sent many NACKs in the last second; uplink=true is from Janus' perspective):
                        Log.d(TAG, "[JANUS] slowlink is reporting uplink:"+json.getBoolean("uplink")+" nacks:"+json.getString("nacks"));
                    } else if(janus.equals("hangup")){
                        //PeerConnection closed for a DTLS alert (normal shutdown):
                        Log.d(TAG, "[JANUS] WebRTC is closed.");
                    } else if(janus.equals("event")){
                        Log.d(TAG, "[JANUS]Server Side event is received.");
                        //plugin에서 발생한 이벤트들로, 개별 파싱 필요
                        //audiobridge의 경우
                        //plugindata
                        //  plugin:"janus.plugin.audiobridge"
                        //  data:
                        //      audiobridge:event
                        //      room:
                        //      participants 또는 leaving
                        //      JANUS의 경우 API가 일관성이 없음..API 서버에서 Wrapping 해야 할듯 함
                    }
                }
            } catch (Exception e){
                Log.d(TAG,"WebSocket Data Parsing Error:"+e.getMessage());
            }
        }
        @Override
        public void onRawTextMessage(byte[] payload) {}

        @Override
        public void onBinaryMessage(byte[] payload) {}
    }

    //A Class for observing SDP Change
    private class SDPObserver implements SdpObserver {
        private SessionDescription localSdp;

        @Override
        public void onCreateSuccess(SessionDescription origSdp) {
            Log.d(TAG,"SessionDescriptor create success.");

            if (localSdp != null) {
                Log.d(TAG,"Multiple SDP create.");
                return;
            }
            String sdpDescription = origSdp.description;
            if (mModAudioPrefer) {
                Log.d(TAG,"Prefer Codec:"+mPreferAudioCodec);
                sdpDescription = preferCodec(sdpDescription, mPreferAudioCodec, true);
            }
            final SessionDescription sdp = new SessionDescription(origSdp.type, sdpDescription);
            localSdp = sdp;
            mPeerConnection.setLocalDescription(mSDPObserver, sdp);
        }

        @Override
        public void onSetSuccess() {
            if(mPeerConnection==null) return;
            if(mIsInitiator){
                if(mPeerConnection.getRemoteDescription()==null) {
                    Log.d(TAG, "Send LocalDescriptor to MediaServer");
                    runOnUiThread(new Runnable() {
                        public void run() {
                            //Send Local SDP To Signaling Server
                            try{
                                JSONObject jsep=new JSONObject();
                                jsep.put("type",localSdp.type);
                                jsep.put("sdp",localSdp.description);
                                String sendOfferTransaction=getTrxId();
                                JSONObject request=new JSONObject();
                                request.put("janus","message");
                                request.put("transaction",sendOfferTransaction);
                                request.put("session_id",mJanusSessionId);
                                request.put("handle_id",mJanusHandleId);
                                JSONObject body=new JSONObject();
                                body.put("request","configure");
                                body.put("muted",false);
                                request.put("body",body);
                                request.put("jsep",jsep);
                                Log.d(TAG, "[JANUS_SEND] Send sdpOffer msg :"+request.toString());
                                mTransactionQueue.put(sendOfferTransaction,"OFFER");
                                mConnection.sendTextMessage(request.toString());
                            } catch (Exception e){
                                Log.d(TAG,"Exception occured when send sdpoffer:"+e.getMessage());
                            }
                        }
                    });
                } else {
                    Log.d(TAG, "We just set remotedescriptor. drain remote/local ICE Candidate");
                    drainRemoteCandidates();
                }
            } else {
                if(mPeerConnection.getLocalDescription()!=null){
                    Log.d(TAG,"Local SDP set successfully");
                    drainRemoteCandidates();
                } else {
                    Log.d(TAG, "Remote SDP set successfully");
                }
            }
        }
        @Override
        public void onCreateFailure(String s) {

        }
        @Override
        public void onSetFailure(String s) {

        }
    }

    //A Class for observing PeerConnection CHange
    private class PCObserver implements PeerConnection.Observer{
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {

        }
        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {

        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {

        }
        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            //This is called when local IceCandidate is occured.
            Log.d(TAG,"Local onIceCandidate event");
            if(mOfferReceived) {
                Log.d(TAG,"onIceCandidate event");
                JSONObject request=new JSONObject();
                String iceCandidateTrx=getTrxId();
                try {
                    request.put("janus", "trickle");
                    request.put("session_id",mJanusSessionId);
                    request.put("handle_id",mJanusHandleId);
                    request.put("candidate",iceCandidate.sdp);
                    request.put("transaction",iceCandidateTrx);
                    Log.d(TAG, "[JANUS_SEND] Send icecandidate msg :"+request.toString());
                    mTransactionQueue.put(iceCandidateTrx,"SEND_ICE");
                    mConnection.sendTextMessage(request.toString());
                } catch (Exception e){
                    Log.d(TAG, "[JANUS_SEND] Exception when sending icecandidate msg :"+e.getMessage());
                }
            } else {
                queuedLocalCandidates.add(iceCandidate);
            }
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            Log.d(TAG,"onAddStream event");
            if (mPeerConnection == null) {
                return;
            }
            if (mediaStream.audioTracks.size() > 1 || mediaStream.videoTracks.size() > 1) {
                Log.d(TAG,"Weird-looking stream: " + mediaStream);
                return;
            }
            if (mediaStream.audioTracks.size() == 1) {
                Log.d(TAG,"audioTracks set");
                mRemoteAudioTrack=mediaStream.audioTracks.get(0);
                mRemoteAudioTrack.setEnabled(true);
            }
        }
        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            mediaStream.audioTracks.get(0).dispose();
        }
        @Override
        public void onDataChannel(DataChannel dataChannel) {

        }
        @Override
        public void onRenegotiationNeeded() {

        }

        @Override
        public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {

        }
    }

    private String preferCodec(String sdpDescription, String codec, boolean isAudio) {
        String[] lines = sdpDescription.split("\r\n");
        int mLineIndex = -1;
        String codecRtpMap = null;
        // a=rtpmap:<payload type> <encoding name>/<clock rate> [/<encoding parameters>]
        String regex = "^a=rtpmap:(\\d+) " + codec + "(/\\d+)+[\r]?$";
        Pattern codecPattern = Pattern.compile(regex);
        String mediaDescription = "m=video ";
        if (isAudio) {
            mediaDescription = "m=audio ";
        }
        for (int i = 0; (i < lines.length) && (mLineIndex == -1 || codecRtpMap == null); i++) {
            if (lines[i].startsWith(mediaDescription)) {
                mLineIndex = i;
                continue;
            }
            Matcher codecMatcher = codecPattern.matcher(lines[i]);
            if (codecMatcher.matches()) {
                codecRtpMap = codecMatcher.group(1);
            }
        }
        if (mLineIndex == -1) {
            Log.w(TAG, "No " + mediaDescription + " line, so can't prefer " + codec);
            return sdpDescription;
        }
        if (codecRtpMap == null) {
            Log.w(TAG, "No rtpmap for " + codec);
            return sdpDescription;
        }
        Log.d(TAG, "Found " + codec + " rtpmap " + codecRtpMap + ", prefer at " + lines[mLineIndex]);
        String[] origMLineParts = lines[mLineIndex].split(" ");
        if (origMLineParts.length > 3) {
            StringBuilder newMLine = new StringBuilder();
            int origPartIndex = 0;
            // Format is: m=<media> <port> <proto> <fmt> ...
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(origMLineParts[origPartIndex++]).append(" ");
            newMLine.append(codecRtpMap);
            for (; origPartIndex < origMLineParts.length; origPartIndex++) {
                if (!origMLineParts[origPartIndex].equals(codecRtpMap)) {
                    newMLine.append(" ").append(origMLineParts[origPartIndex]);
                }
            }
            lines[mLineIndex] = newMLine.toString();
            Log.d(TAG, "Change media description: " + lines[mLineIndex]);
        } else {
            Log.e(TAG, "Wrong SDP media description format: " + lines[mLineIndex]);
        }
        StringBuilder newSdpDescription = new StringBuilder();
        for (String line : lines) {
            newSdpDescription.append(line).append("\r\n");
        }
        return newSdpDescription.toString();
    }

    private void processRemoteCandidate(JSONObject candidateIn) {
        Log.d(TAG,"Add Remote IceCandidate:"+candidateIn.toString());
        try {
            IceCandidate candidate = new IceCandidate(
                    (String) candidateIn.get("sdpMid"),
                    candidateIn.getInt("sdpMLineIndex"),
                    (String) candidateIn.get("candidate")
            );
            Log.d(TAG,"Candidate ToString:"+candidate.toString());
            //mPeerConnection.addIceCandidate(candidate);
            if(queuedRemoteCandidates==null) {
                mPeerConnection.addIceCandidate(candidate);
            } else queuedRemoteCandidates.add(candidate);
        } catch (Exception e){
            Log.d(TAG,"processRemoteCandidate Ex:"+e.toString());
        }

    }
    private void onAudioManagerDevicesChanged(
            final AppRTCAudioManager.AudioDevice device, final Set<AppRTCAudioManager.AudioDevice> availableDevices) {
        Log.d(TAG, "onAudioManagerDevicesChanged: " + availableDevices + ", " + "selected: " + device);
    }
    private void drainRemoteCandidates() {
        if (queuedRemoteCandidates != null) {
            Log.d(TAG, "Add " + queuedRemoteCandidates.size() + " remote candidates");
            for (IceCandidate candidate : queuedRemoteCandidates) {
                mPeerConnection.addIceCandidate(candidate);
            }
            queuedRemoteCandidates = null;
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
}
