import 'package:nearby_connections/nearby_connections.dart';

class MeshManager {
  MeshManager({
    Nearby? nearby,
    Strategy strategy = Strategy.P2P_CLUSTER,
  })  : _nearby = nearby ?? Nearby(),
        _strategy = strategy;

  final Nearby _nearby;
  final Strategy _strategy;

  Future<void> startMesh({
    required String userName,
    String serviceId = 'com.example.bluetooth_based_communication_network',
    required OnConnectionInitiated onConnectionInitiated,
    required OnConnectionResult onConnectionResult,
    required OnDisconnected onDisconnected,
    required OnEndpointFound onEndpointFound,
    required OnEndpointLost onEndpointLost,
  }) async {
    await _nearby.startAdvertising(
      userName,
      _strategy,
      onConnectionInitiated: onConnectionInitiated,
      onConnectionResult: onConnectionResult,
      onDisconnected: onDisconnected,
      serviceId: serviceId,
    );
    await _nearby.startDiscovery(
      userName,
      _strategy,
      onEndpointFound: onEndpointFound,
      onEndpointLost: onEndpointLost,
      serviceId: serviceId,
    );
  }

  Future<void> stopMesh() async {
    await _nearby.stopAdvertising();
    await _nearby.stopDiscovery();
  }
}
