package io.github.edufolly.flutterbluetoothserial;

public class Constants {

	// Plugin
	static final String TAG = "FlutterBluePlugin";

	static final String PLUGIN_NAMESPACE 	= "flutter_bluetooth_serial";
	static final String METHODS_CHANNEL		= PLUGIN_NAMESPACE + "/methods";
	static final String STATE_CHANNEL		= PLUGIN_NAMESPACE + "/state";
	static final String DISCOVERY_CHANNEL	= PLUGIN_NAMESPACE + "/discovery";
	static final String READ_CHANNEL		= PLUGIN_NAMESPACE + "/read/";

	// Permissions and request constants
	static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1451;
	static final int REQUEST_ENABLE_BLUETOOTH = 1337;
	static final int REQUEST_DISCOVERABLE_BLUETOOTH = 2137;

}
