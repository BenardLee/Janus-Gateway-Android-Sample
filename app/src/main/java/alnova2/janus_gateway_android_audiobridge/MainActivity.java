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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity implements JanusProtocolCallback {
    private final String TAG="MainActivity";
    private JanusProtocol mJanusProtocol;

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

    private ConcurrentLinkedQueue<IceCandidate> queuedRemoteCandidates;

    //Audio Configurations
    private final String wsuri="ws://192.168.0.55:8888/janus";
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
    private ConcurrentLinkedQueue<IceCandidate> queuedLocalCandidates;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        //1. Setup UI Components
        mConnectButton=(ToggleButton)findViewById(R.id.toggleButton);
        mConnectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(mConnectButton.isChecked()){
                    Log.d(TAG,"1. connect websocket server addr:"+wsuri);
                    mJanusProtocol.connect(wsuri);
                } else {
                    if(mJanusProtocol.isConnected()) {
                        mJanusProtocol.disconnect();
                        mConnectButton.setChecked(false);
                    }
                }
            }
        });
        mStartButton =(Button)findViewById(R.id.startButton);
        mStartButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG,"2. create session_id");
                mJanusProtocol.createSession();
            }
        });
        mStopButton=(Button)findViewById(R.id.stoptButton);
        mStopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mJanusProtocol.destroySession();
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

        //Create JanusProtocol Object
        mJanusProtocol=new JanusProtocol(this);
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
        iceServers.add(new PeerConnection.IceServer("stun:stun.l.google.com:19302"));
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

    //this is for implementing janusprotocolcallback
    @Override
    public void onOpened(boolean isSuccess, Exception ex) {

    }

    @Override
    public void onClosed(String msg) {

    }

    @Override
    public void onCreateSessionReply(boolean isSuccess, Exception ex) {
        if(isSuccess){
            Log.d(TAG,"3. attach plugin");
            mJanusProtocol.attachPlugin("janus.plugin.audiobridge","test-janusaudioplugin");
        }
    }

    @Override
    public void onAttachPluginReply(boolean isSuccess, Exception ex) {
        if(isSuccess){
            Log.d(TAG,"4. attach janus.plugin.audiobridge success. start_keepalive");
            mJanusProtocol.startKeepAlive();
            Log.d(TAG,"5. join room janus.plugin.audiobridge");
            try{
                JSONObject body=new JSONObject();
                body.put("request","join");
                body.put("room",Integer.parseInt(mRoomIdText.getText().toString()));
                body.put("display","Hello Android");
                mJanusProtocol.sendPluginMsg("janus.plugin.audiobridge",body);
            } catch (Exception e){
                Log.d(TAG,"onAttachPluginReply Error:"+e.getMessage());
            }
        }
    }

    @Override
    public void onPluginEvent(JSONObject msg) {
        try {
            JSONObject plugin_data=msg.getJSONObject("plugindata");
            if(plugin_data.getString("plugin").equals("janus.plugin.audiobridge")){
                JSONObject data=plugin_data.getJSONObject("data");
                if(data.getString("audiobridge").equals("joined")){
                    Log.d(TAG,"6. join room janus.plugin.audiobridge success. getUserMedia");
                    setAudioProcessing();
                    startAudio();
                    createLocalMediaStream();
                    Log.d(TAG,"7. Creating PeerConnection");
                    createPeerConnection();
                    if(mPeerConnection!=null){
                        Log.d(TAG,"8. Created SDP offer");
                        mIsInitiator=true;
                        mSDPObserver=new SDPObserver();
                        mPeerConnection.createOffer(mSDPObserver,mLocalMediaConstrants);
                    }
                } else if(data.getString("audiobridge").equals("event")){
                    if(data.getString("result").equals("ok")){
                        if(msg.has("jsep")){
                            mOfferReceived=true;
                            JSONObject jsep=msg.getJSONObject("jsep");
                            SessionDescription sdpAnswer = new SessionDescription(SessionDescription.Type.fromCanonicalForm("answer"), jsep.getString("sdp"));
                            mIsInitiator=false;
                            mPeerConnection.setRemoteDescription(mSDPObserver,sdpAnswer);
                            //while(!queuedLocalCandidates.isEmpty()){
                            //    IceCandidate rCandidate=queuedLocalCandidates.remove();
                            //    mJanusProtocol.sendTricle("janus.plugin.audiobridge",rCandidate);
                            //}
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG,"onPluginEvent Error:"+e.getMessage());
        }
    }

    @Override
    public void onError(String order, JSONObject msg) {
        Log.d(TAG,"Error Received Order:"+order+" msg:"+msg.toString());
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
                                JSONObject body=new JSONObject();
                                body.put("request","configure");
                                body.put("muted",false);
                                mJanusProtocol.sendPluginMsgWithJsep("janus.plugin.audiobridge",body,jsep);
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
            //if(mOfferReceived) mJanusProtocol.sendTricle("janus.plugin.audiobridge",iceCandidate);
            //else queuedLocalCandidates.add(iceCandidate);
            mJanusProtocol.sendTricle("janus.plugin.audiobridge",iceCandidate);

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
}
