import 'package:flutter/material.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const BluetoothMeshApp());
}

class BluetoothMeshApp extends StatelessWidget {
  const BluetoothMeshApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Bluetooth Mesh',
      theme: ThemeData(useMaterial3: true),
      home: const Scaffold(
        body: Center(
          child: Text('Bluetooth Mesh Scaffold'),
        ),
      ),
    );
  }
}
