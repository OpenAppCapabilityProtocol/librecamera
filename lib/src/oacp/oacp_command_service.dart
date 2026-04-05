import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

enum OacpCommandType { takePhoto, startVideoRecording }

enum OacpCameraPreference { appDefault, front, rear }

class OacpCommand {
  const OacpCommand({
    required this.requestId,
    required this.type,
    required this.cameraPreference,
    this.durationSeconds,
  });

  final String requestId;
  final OacpCommandType type;
  final OacpCameraPreference cameraPreference;
  final int? durationSeconds;
}

/// Bridges OACP intents from MainActivity to Dart camera logic.
///
/// MainActivity maps the 4 action variants into a normalized payload with
/// command, camera, and optional duration_seconds fields, then calls
/// handleOacpCommand on this channel.
class OacpCommandService {
  OacpCommandService._();

  static final OacpCommandService instance = OacpCommandService._();
  static const MethodChannel _channel = MethodChannel(
    'com.iakmds.librecamera/oacp',
  );

  final StreamController<OacpCommand> _controller =
      StreamController<OacpCommand>.broadcast();

  OacpCommand? _pendingCommand;
  bool _initialized = false;

  Stream<OacpCommand> get commands => _controller.stream;

  Future<void> initialize() async {
    if (_initialized) return;
    _initialized = true;

    _channel.setMethodCallHandler((call) async {
      if (call.method != 'handleOacpCommand') {
        throw MissingPluginException('Unsupported method ${call.method}');
      }

      final rawArguments = call.arguments;
      if (rawArguments is! Map<Object?, Object?>) {
        throw ArgumentError.value(
          rawArguments,
          'call.arguments',
          'Expected a map payload for OACP commands.',
        );
      }

      final map = rawArguments.map(
        (key, value) => MapEntry(key.toString(), value),
      );

      final commandStr = map['command'] as String?;
      final cameraStr = map['camera'] as String?;

      final command = OacpCommand(
        requestId:
            (map['requestId'] as String?) ??
            DateTime.now().millisecondsSinceEpoch.toString(),
        type: switch (commandStr) {
          'take_photo' => OacpCommandType.takePhoto,
          'start_video_recording' => OacpCommandType.startVideoRecording,
          _ => throw ArgumentError.value(
            commandStr,
            'command',
            'Unsupported OACP command',
          ),
        },
        cameraPreference: switch (cameraStr) {
          'front' => OacpCameraPreference.front,
          'rear' => OacpCameraPreference.rear,
          _ => OacpCameraPreference.appDefault,
        },
        durationSeconds: map['duration_seconds'] as int?,
      );

      debugPrint('OacpDart: Received command: ${command.type} camera=${command.cameraPreference} duration=${command.durationSeconds}');
      debugPrint('OacpDart: command=${command.type} camera=${command.cameraPreference} duration=${command.durationSeconds}');
      _pendingCommand = command;
      _controller.add(command);
    });
  }

  OacpCommand? consumePendingCommand() {
    final command = _pendingCommand;
    _pendingCommand = null;
    return command;
  }
}
