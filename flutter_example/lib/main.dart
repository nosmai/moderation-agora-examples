import 'dart:async';

import 'package:agora_rtc_engine/agora_rtc_engine.dart';
import 'package:flutter/material.dart';
import 'package:moderation_agora_bridge_flutter/moderation_agora_bridge_flutter.dart';
import 'package:nosmai_moderation_sdk/nosmai_moderation_sdk.dart';
import 'package:permission_handler/permission_handler.dart';

// ---- Config (pass with --dart-define) -------------------------------------
// The Agora token expires (~24h); regenerate on error 109. For a testing-mode
// Agora project, leave AGORA_TOKEN empty.
const String appId =
    String.fromEnvironment("AGORA_APP_ID", defaultValue: "YOUR_AGORA_APP_ID");
const String channel =
    String.fromEnvironment("AGORA_CHANNEL", defaultValue: "YOUR_CHANNEL_NAME");
const String token = String.fromEnvironment("AGORA_TOKEN", defaultValue: "");
// Nosmai Moderation license key, registered for this app package/bundle id.
const String licenseKey =
    String.fromEnvironment("NOSMAI_LICENSE_KEY", defaultValue: "NOSMAI-XXXX");
// ---------------------------------------------------------------------------

// Initialize the Nosmai SDK once; both screens share it.
bool _nosmaiReady = false;
String _nosmaiError = '';
Future<bool> ensureNosmaiInit() async {
  if (_nosmaiReady) return true;
  try {
    final lic = await NosmaiModeration.initialize(
      licenseKey,
      models: const [NosmaiModel.objectDetection, NosmaiModel.nsfw],
    );
    _nosmaiReady = lic.success == true;
    if (!_nosmaiReady) _nosmaiError = lic.error ?? 'license failed';
  } catch (e) {
    _nosmaiError = '$e';
  }
  return _nosmaiReady;
}

void main() => runApp(const MyApp());

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Nosmai Moderation',
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      home: const HomeScreen(),
    );
  }
}

// ---- Home: pick a demo ----------------------------------------------------
class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Nosmai Moderation')),
      body: Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            FilledButton.icon(
              icon: const Icon(Icons.videocam),
              label: const Text('Camera Moderation'),
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute(
                    builder: (_) => const CameraModerationScreen()),
              ),
            ),
            const SizedBox(height: 16),
            FilledButton.icon(
              icon: const Icon(Icons.cast),
              label: const Text('Agora + Moderation'),
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const AgoraModerationScreen()),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---- Camera-only moderation (Nosmai's own camera, no Agora) ----------------
class CameraModerationScreen extends StatefulWidget {
  const CameraModerationScreen({super.key});

  @override
  State<CameraModerationScreen> createState() => _CameraModerationScreenState();
}

class _CameraModerationScreenState extends State<CameraModerationScreen> {
  StreamSubscription<NosmaiResult>? _sub;
  NosmaiResult? _result;
  bool _ready = false;
  String _status = 'Starting...';

  @override
  void initState() {
    super.initState();
    _start();
  }

  Future<void> _start() async {
    final cam = await Permission.camera.request();
    if (!cam.isGranted) {
      setState(() => _status = 'Camera permission denied');
      return;
    }
    if (!await ensureNosmaiInit()) {
      setState(() => _status = 'Nosmai init failed: $_nosmaiError');
      return;
    }
    _sub = NosmaiLive.results().listen((r) {
      if (mounted) setState(() => _result = r);
    });
    await NosmaiLive.start();
    if (mounted) setState(() => _ready = true);
  }

  @override
  void dispose() {
    _sub?.cancel();
    NosmaiLive.stop();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Camera Moderation')),
      body: Column(
        children: [
          Expanded(
            child: _ready
                ? const NosmaiCameraPreview()
                : Center(child: Text(_status)),
          ),
          _VerdictBanner(_result),
          const SizedBox(height: 12),
        ],
      ),
    );
  }
}

// ---- Agora live stream + moderation (via the bridge) ----------------------
class AgoraModerationScreen extends StatefulWidget {
  const AgoraModerationScreen({super.key});

  @override
  State<AgoraModerationScreen> createState() => _AgoraModerationScreenState();
}

class _AgoraModerationScreenState extends State<AgoraModerationScreen> {
  RtcEngine? _engine;
  bool _joined = false;
  String _status = 'Initializing...';
  NosmaiResult? _verdict;
  StreamSubscription<NosmaiResult>? _resultSub;

  @override
  void initState() {
    super.initState();
    _init();
  }

  Future<void> _init() async {
    await [Permission.camera, Permission.microphone].request();
    if (!await ensureNosmaiInit()) {
      setState(() => _status = 'Nosmai init failed: $_nosmaiError');
      return;
    }

    // Live detection on the frames the bridge feeds in (no device camera here).
    await NosmaiLive.startExternal();
    _resultSub = NosmaiLive.results().listen((r) {
      if (mounted) setState(() => _verdict = r);
    });

    // Only line that differs from a plain Agora setup: create the engine from
    // the bridge's shared native handle so it can tap and moderate the frames.
    final handle = await ModerationAgoraBridge.getNativeHandle(agoraAppId: appId);
    final engine = createAgoraRtcEngine(sharedNativeHandle: handle);

    await engine.initialize(const RtcEngineContext(
      appId: appId,
      channelProfile: ChannelProfileType.channelProfileLiveBroadcasting,
    ));
    engine.registerEventHandler(RtcEngineEventHandler(
      onJoinChannelSuccess: (c, e) =>
          setState(() {
            _joined = true;
            _status = 'Live';
          }),
      onLeaveChannel: (c, s) =>
          setState(() {
            _joined = false;
            _status = 'Left';
          }),
      onError: (err, msg) => setState(() => _status = 'Error ${err.value()}: $msg'),
    ));
    await engine.enableVideo();
    await engine.startPreview();
    setState(() {
      _engine = engine;
      _status = 'Ready';
    });
  }

  Future<void> _join() async {
    setState(() => _status = 'Joining...');
    await _engine?.joinChannel(
      token: token,
      channelId: channel,
      uid: 0,
      options: const ChannelMediaOptions(
        clientRoleType: ClientRoleType.clientRoleBroadcaster,
        publishCameraTrack: true,
        publishMicrophoneTrack: true,
      ),
    );
  }

  Future<void> _leave() async => _engine?.leaveChannel();

  Future<void> _switchCamera() async {
    await _engine?.switchCamera();
    await ModerationAgoraBridge.notifyCameraSwitch();
  }

  @override
  void dispose() {
    _resultSub?.cancel();
    NosmaiLive.stop();
    ModerationAgoraBridge.disposeNative();
    _engine?.leaveChannel();
    _engine?.release();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final engine = _engine;
    return Scaffold(
      appBar: AppBar(title: const Text('Agora + Moderation')),
      body: Column(
        children: [
          Expanded(
            child: engine == null
                ? Center(child: Text(_status))
                : AgoraVideoView(
                    controller: VideoViewController(
                      rtcEngine: engine,
                      canvas: const VideoCanvas(uid: 0),
                    ),
                  ),
          ),
          _VerdictBanner(_verdict),
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text('Status: $_status'),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 16),
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                ElevatedButton(
                  onPressed: engine == null || _joined ? null : _join,
                  child: const Text('Start stream'),
                ),
                ElevatedButton(
                  onPressed: engine == null ? null : _switchCamera,
                  child: const Text('Switch camera'),
                ),
                ElevatedButton(
                  onPressed: _joined ? _leave : null,
                  child: const Text('Stop'),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

// ---- Shared SAFE / UNSAFE banner ------------------------------------------
class _VerdictBanner extends StatelessWidget {
  const _VerdictBanner(this.verdict);

  final NosmaiResult? verdict;

  @override
  Widget build(BuildContext context) {
    final v = verdict;
    if (v == null) return const SizedBox.shrink();
    final unsafe = v.isUnsafe ?? false;
    final objects = (v.detections ?? const [])
        .map((d) => d?.category?.name)
        .where((n) => n != null)
        .join(', ');
    final nsfw = v.nsfw?.name ?? 'safe';
    return Container(
      width: double.infinity,
      color: unsafe ? Colors.red.shade100 : Colors.green.shade100,
      padding: const EdgeInsets.all(12),
      child: Text(
        '${unsafe ? 'UNSAFE' : 'SAFE'}  -  NSFW: $nsfw'
        '${objects.isNotEmpty ? '  -  $objects' : ''}',
        textAlign: TextAlign.center,
        style: const TextStyle(fontWeight: FontWeight.bold),
      ),
    );
  }
}
