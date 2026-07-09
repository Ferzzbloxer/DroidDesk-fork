import 'package:flutter/material.dart';
import 'package:flutter_rfb/flutter_rfb.dart';
import 'package:droiddesk/theme/droid_theme.dart';

class VncDesktopScreen extends StatefulWidget {
  const VncDesktopScreen({super.key});

  @override
  State<VncDesktopScreen> createState() => _VncDesktopScreenState();
}

class _VncDesktopScreenState extends State<VncDesktopScreen> {
  bool _showControls = true;
  bool _connected = true; // We start trying to connect immediately
  int _retryKey = 0;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      body: Stack(
        children: [
          // The VNC Viewer
          Center(
            child: InteractiveViewer(
              constrained: true,
              maxScale: 5.0,
              minScale: 0.5,
              child: Builder(
                builder: (context) {
                  if (!_connected) {
                    return const Center(
                      child: Text(
                        'Disconnected',
                        style: TextStyle(color: Colors.white, fontSize: 18),
                      ),
                    );
                  }
                  
                  return RemoteFrameBufferWidget(
                    key: ValueKey(_retryKey), // Force rebuild on retry
                    hostName: '127.0.0.1',
                    port: 5900,
                    password: 'password', // As configured in our LinuxRuntime bash script
                    connectingWidget: Column(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const CircularProgressIndicator(color: DroidTheme.primary),
                        const SizedBox(height: 16),
                        Text(
                          'Starting VNC Server...',
                          style: DroidTheme.bodyLg.copyWith(color: Colors.white70),
                        ),
                      ],
                    ),
                    onError: (error) {
                      debugPrint('VNC Connection Error: $error');
                      // Retry after a delay because proot takes time to spin up
                      Future.delayed(const Duration(seconds: 2), () {
                        if (mounted && _connected) {
                          // Force a rebuild to retry the connection
                          setState(() {
                            _retryKey++;
                          });
                        }
                      });
                    },
                  );
                },
              ),
            ),
          ),
          
          // Overlay Controls
          if (_showControls)
            Positioned(
              top: 40, // Safe area roughly
              left: 16,
              right: 16,
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  FloatingActionButton.small(
                    heroTag: 'back_btn',
                    backgroundColor: DroidTheme.cardBg.withOpacity(0.8),
                    onPressed: () {
                      Navigator.pop(context);
                    },
                    child: const Icon(Icons.close, color: Colors.white),
                  ),
                  FloatingActionButton.small(
                    heroTag: 'toggle_controls_btn',
                    backgroundColor: DroidTheme.cardBg.withOpacity(0.8),
                    onPressed: () {
                      setState(() {
                        _showControls = false;
                      });
                    },
                    child: const Icon(Icons.visibility_off, color: Colors.white),
                  ),
                ],
              ),
            ),
            
          // Hidden toggle button to bring controls back
          if (!_showControls)
            Positioned(
              top: 40,
              right: 16,
              child: GestureDetector(
                onTap: () {
                  setState(() {
                    _showControls = true;
                  });
                },
                child: Container(
                  width: 40,
                  height: 40,
                  color: Colors.transparent, // Invisible touch target in the corner
                ),
              ),
            ),
        ],
      ),
    );
  }
}
