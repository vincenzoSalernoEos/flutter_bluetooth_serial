package io.github.edufolly.flutterbluetoothserial;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry;
import io.flutter.view.FlutterNativeView;

public class FlutterBluetoothSerialCallHandler implements MethodChannel.MethodCallHandler, PluginRegistry.RequestPermissionsResultListener, PluginRegistry.ActivityResultListener, PluginRegistry.ViewDestroyListener {

	//valorizzate in input dal costruttore
	private final BinaryMessenger messenger;
	private final Context context;
	private final MethodChannel methodChannel;

	//valorizzate nel costruttore in maniera dinamica
	private final BluetoothAdapter bluetoothAdapter;

	//valorizzate in un secondo momento tramite set
	private Activity activity;

	//--------------------------OLD--------------------------//
	private MethodChannel.Result pendingResultForActivityResult;

	// State
	private BroadcastReceiver stateReceiver;
	private EventChannel.EventSink stateSink;

	// Pairing requests
	private BroadcastReceiver pairingRequestReceiver;
	private boolean isPairingRequestHandlerSet = false;
	private BroadcastReceiver bondStateBroadcastReceiver = null;

	private EventChannel.EventSink discoverySink;
	private BroadcastReceiver discoveryReceiver;

	// Connections
	/// Contains all active connections. Maps ID of the connection with plugin data channels.
	protected final HashMap<Integer, BluetoothConnectionWrapper> connections = new HashMap<>();

	/// Last ID given to any connection, used to avoid duplicate IDs
	private int lastConnectionId = 0;
	//--------------------------OLD--------------------------//

	public void setActivity(Activity activity) {
		this.activity = activity;
	}

	public FlutterBluetoothSerialCallHandler(BinaryMessenger messenger, Context context, MethodChannel methodChannel) {
		this.messenger = messenger;
		this.context = context;
		this.methodChannel = methodChannel;

		// General Bluetooth
		//TODO capire se prendendolo tramite context invece che activity funziona lo stesso
//		BluetoothManager bluetoothManager = (BluetoothManager) registrar.activity().getSystemService(Context.BLUETOOTH_SERVICE);
		BluetoothManager bluetoothManager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
		assert bluetoothManager != null;
		this.bluetoothAdapter = bluetoothManager.getAdapter();

		//declare receivers
		startStateReceiver();
		startPairingRequestReceiver();
		startDiscoveryReceiver();
	}

	private void startStateReceiver() {
		if(stateReceiver == null) {
			stateReceiver = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					if (stateSink == null) {
						return;
					}

					if(BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
						// Disconnect all connections
						for (BluetoothConnection connection : connections.values()) {
							connection.disconnect();
						}
						connections.clear();

						stateSink.success(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothDevice.ERROR));
					}
				}
			};
		}
	}

	private void startPairingRequestReceiver() {
		if(pairingRequestReceiver == null) {
			pairingRequestReceiver = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					// Ignore other actions
					if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
						final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
						final int pairingVariant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR);
						Log.d(Constants.TAG, "Pairing request (variant " + pairingVariant + ") incoming from " + device.getAddress());
						switch (pairingVariant) {
							case BluetoothDevice.PAIRING_VARIANT_PIN:
								// Simplest method - 4 digit number
							{
								final PendingResult broadcastResult = this.goAsync();

								Map<String, Object> arguments = new HashMap<>();
								arguments.put("address", device.getAddress());
								arguments.put("variant", pairingVariant);

								methodChannel.invokeMethod("handlePairingRequest", arguments, new MethodChannel.Result() {
									@Override
									public void success(Object handlerResult) {
										Log.d(Constants.TAG, handlerResult.toString());
										if (handlerResult instanceof String) {
											try {
												final String passkeyString = (String) handlerResult;
												final byte[] passkey = passkeyString.getBytes();
												Log.d(Constants.TAG, "Trying to set passkey for pairing to " + passkeyString);
												device.setPin(passkey);
												broadcastResult.abortBroadcast();
											} catch (Exception ex) {
												Log.e(Constants.TAG, ex.getMessage());
												ex.printStackTrace();
												// @TODO , passing the error
												//result.error("bond_error", "Setting passkey for pairing failed", exceptionToString(ex));
											}
										} else {
											Log.d(Constants.TAG, "Manual pin pairing in progress");
											//Intent intent = new Intent(BluetoothAdapter.ACTION_PAIRING_REQUEST);
											//intent.putExtra(BluetoothDevice.EXTRA_DEVICE, device);
											//intent.putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, pairingVariant)
											ContextCompat.startActivity(activity, intent, null);
										}
										broadcastResult.finish();
									}

									@Override
									public void notImplemented() {
										throw new UnsupportedOperationException();
									}

									@Override
									public void error(String code, String message, Object details) {
										throw new UnsupportedOperationException();
									}
								});
								break;
							}

							// Note: `BluetoothDevice.PAIRING_VARIANT_PASSKEY` seems to be unsupported anyway... Probably is abandoned.
							// See https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/bluetooth/BluetoothDevice.java#1528

							case BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION:
								// Displayed passkey on the other device should be the same as received here.
							case 3: //case BluetoothDevice.PAIRING_VARIANT_CONSENT: // @TODO , Symbol not found?
								// The simplest, but much less secure method - just yes or no, without any auth.
								// Consent type can use same code as passkey confirmation since passed passkey,
								// which is 0 or error at the moment, should not be used anyway by common code.
							{
								final int pairingKey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR);

								Map<String, Object> arguments = new HashMap<>();
								arguments.put("address", device.getAddress());
								arguments.put("variant", pairingVariant);
								arguments.put("pairingKey", pairingKey);

								final PendingResult broadcastResult = this.goAsync();
								methodChannel.invokeMethod("handlePairingRequest", arguments, new MethodChannel.Result() {
									@Override
									public void success(Object handlerResult) {
										if (handlerResult instanceof Boolean) {
											try {
												final boolean confirm = (Boolean) handlerResult;
												Log.d(Constants.TAG, "Trying to set pairing confirmation to " + confirm + " (key: " + pairingKey + ")");
												// @WARN `BLUETOOTH_PRIVILEGED` permission required, but might be unavailable for thrid party apps on newer versions of Androids.
												broadcastResult.abortBroadcast();
											} catch (Exception ex) {
												Log.e(Constants.TAG, ex.getMessage());
												ex.printStackTrace();
												// @TODO , passing the error
												//result.error("bond_error", "Auto-confirming pass key failed", exceptionToString(ex));
											}
										} else {
											Log.d(Constants.TAG, "Manual passkey confirmation pairing in progress (key: " + pairingKey + ")");
											ContextCompat.startActivity(activity, intent, null);
										}
										broadcastResult.finish();
									}

									@Override
									public void notImplemented() {
										throw new UnsupportedOperationException();
									}

									@Override
									public void error(String code, String message, Object details) {
										Log.e(Constants.TAG, code + " " + message);
										throw new UnsupportedOperationException();
									}
								});
								break;
							}

							case 4: //case BluetoothDevice.PAIRING_VARIANT_DISPLAY_PASSKEY: // @TODO , Symbol not found?
								// This pairing method requires to enter the generated and displayed pairing key
								// on the remote device. It looks like basic asymmetric cryptography was used.
							case 5: //case BluetoothDevice.PAIRING_VARIANT_DISPLAY_PIN: // @TODO , Symbol not found?
								// Same as previous, but for 4 digit pin.
							{
								final int pairingKey = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR);

								Map<String, Object> arguments = new HashMap<>();
								arguments.put("address", device.getAddress());
								arguments.put("variant", pairingVariant);
								arguments.put("pairingKey", pairingKey);

								methodChannel.invokeMethod("handlePairingRequest", arguments);
								break;
							}

							// Note: `BluetoothDevice.PAIRING_VARIANT_OOB_CONSENT` seems to be unsupported for now, at least at master branch of Android.
							// See https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/bluetooth/BluetoothDevice.java#1559

							// Note: `BluetoothDevice.PAIRING_VARIANT_PIN_16_DIGITS ` seems to be unsupported for now, at least at master branch of Android.
							// See https://android.googlesource.com/platform/frameworks/base/+/master/core/java/android/bluetooth/BluetoothDevice.java#1559

							default:
								// Only log other pairing variants
								Log.w(Constants.TAG, "Unknown pairing variant: " + pairingVariant);
								break;
						}
					}
				}
			};
		}
	}

	private void startDiscoveryReceiver() {
		if(discoveryReceiver == null) {
			discoveryReceiver = new BroadcastReceiver() {
				@Override
				public void onReceive(Context context, Intent intent) {
					final String action = intent.getAction();
					switch (action) {
						case BluetoothDevice.ACTION_FOUND:
							final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
							//final BluetoothClass deviceClass = intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS); // @TODO . !BluetoothClass!
							//final String extraName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME); // @TODO ? !EXTRA_NAME!
							final int deviceRSSI = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);

							Map<String, Object> discoveryResult = new HashMap<>();
							discoveryResult.put("address", device.getAddress());
							discoveryResult.put("name", device.getName());
							discoveryResult.put("type", device.getType());
							//discoveryResult.put("class", deviceClass); // @TODO . it isn't my priority for now !BluetoothClass!
							discoveryResult.put("isConnected", checkIsDeviceConnected(device));
							discoveryResult.put("bondState", device.getBondState());
							discoveryResult.put("rssi", deviceRSSI);

							Log.d(Constants.TAG, "Discovered " + device.getAddress());
							if (discoverySink != null) {
								discoverySink.success(discoveryResult);
							}
							break;

						case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
							Log.d(Constants.TAG, "Discovery finished");
							try {
								context.unregisterReceiver(discoveryReceiver);
							}
							catch (IllegalArgumentException ex) {
								// Ignore `Receiver not registered` exception
							}

							bluetoothAdapter.cancelDiscovery();

							if (discoverySink != null) {
								discoverySink.endOfStream();
								discoverySink = null;
							}
							break;

						default:
							// Ignore.
							break;
					}
				}
			};
		}
	}

	public EventChannel.StreamHandler getStateStreamHandler() {
		return new EventChannel.StreamHandler() {
			@Override
			public void onListen(Object o, EventChannel.EventSink eventSink) {
				stateSink = eventSink;

				context.registerReceiver(stateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
			}
			@Override
			public void onCancel(Object o) {
				stateSink = null;
				try {
					context.unregisterReceiver(stateReceiver);
				}
				catch (IllegalArgumentException ex) {
					// Ignore `Receiver not registered` exception
				}
			}
		};
	}

	public EventChannel.StreamHandler getDiscoveryStreamHandler() {
		return new EventChannel.StreamHandler() {
			@Override
			public void onListen(Object o, EventChannel.EventSink eventSink) {
				discoverySink = eventSink;
			}

			@Override
			public void onCancel(Object o) {
				Log.d(Constants.TAG, "Canceling discovery (stream closed)");
				try {
					//old: registrar.activeContext()
					context.unregisterReceiver(discoveryReceiver);
				} catch (IllegalArgumentException ex) {
					// Ignore `Receiver not registered` exception
				}

				bluetoothAdapter.cancelDiscovery();

				if (discoverySink != null) {
					discoverySink.endOfStream();
					discoverySink = null;
				}
			}
		};
	}

	/**
	 * Handles the specified method call received from Flutter.
	 *
	 * <p>Handler implementations must submit a result for all incoming calls, by making a single
	 * call on the given {@link MethodChannel.Result} callback. Failure to do so will result in lingering Flutter
	 * result handlers. The result may be submitted asynchronously. Calls to unknown or
	 * unimplemented methods should be handled using {@link MethodChannel.Result#notImplemented()}.
	 *
	 * <p>Any uncaught exception thrown by this method will be caught by the channel implementation
	 * and logged, and an error result will be sent back to Flutter.
	 *
	 * <p>The handler is called on the platform thread (Android main thread). For more details see
	 * <a href="https://github.com/flutter/engine/wiki/Threading-in-the-Flutter-Engine">Threading in
	 * the Flutter Engine</a>.
	 *
	 * @param call   A {@link MethodCall}.
	 * @param result A {@link MethodChannel.Result} used for submitting the result of the call.
	 */
	@Override
	public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
		//if bluetoothAdapter is null, the only one method available is "isAvailable" one
		if (bluetoothAdapter == null) {
			if ("isAvailable".equals(call.method)) {
				result.success(false);
			} else {
				result.error("bluetooth_unavailable", "bluetooth is not available", null);
			}
			return;
		}

		switch (call.method) {
			////////////////////////////////////////
			/* Adapter settings and general */
			case "isAvailable":
				result.success(true);
				break;
			case "isOn":
			case "isEnabled":
				result.success(bluetoothAdapter.isEnabled());
				break;
			case "openSettings":
				if(activity!=null) {
					ContextCompat.startActivity(activity, new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS), null);
					result.success(null);
				}
				break;
			case "requestEnable":
				requestEnableMethod(result);
				break;
			case "requestDisable":
				requestDisableMethod(result);
				break;
			case "ensurePermissions":
				ensurePermissions(result::success);
				break;
			case "getState":
				result.success(bluetoothAdapter.getState());
				break;
			case "getAddress":
				getAddressMethod(result);
				break;
			case "getName":
				result.success(bluetoothAdapter.getName());
				break;
			case "setName":
				setNameMethod(call, result);
				break;
			////////////////////////////////////////////////////////////////////////////////
			/* Discovering and bonding devices */
			case "getDeviceBondState":
				getDeviceBondStateMethod(call, result);
				break;
			case "removeDeviceBond":
				removeDeviceBondMethod(call, result);
				break;
			case "bondDevice":
				bondDeviceMethod(call, result);
				break;
			case "pairingRequestHandlingEnable":
				pairingRequestHandlingEnableMethod(result);
				break;
			case "pairingRequestHandlingDisable":
				pairingRequestHandlingDisableMethod();
				break;
			case "getBondedDevices":
				getBondedDevicesMethod(result);
				break;
			case "isDiscovering":
				result.success(bluetoothAdapter.isDiscovering());
				break;
			case "startDiscovery":
				startDiscoveryMethod(result);
				break;
			case "cancelDiscovery":
				cancelDiscoveryMethod(result);
				break;
			case "isDiscoverable":
				result.success(bluetoothAdapter.getScanMode() == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE);
				break;
			case "requestDiscoverable":
				requestDiscoverableMethod(call, result);
				break;

			////////////////////////////////////////////////////////////////////////////////
			/* Connecting and connection */
			case "connect":
				connectMethod(call, result);
				break;
			case "write":
				writeMethod(call, result);
				break;
			default:
				result.notImplemented();
				break;
		}
	}

	private void requestEnableMethod(@NonNull MethodChannel.Result result) {
		if(activity!=null) {
			if (!bluetoothAdapter.isEnabled()) {
				pendingResultForActivityResult = result;
				ActivityCompat.startActivityForResult(activity, new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), Constants.REQUEST_ENABLE_BLUETOOTH, null);
			} else {
				result.success(true);
			}
		} else {
			result.success(false);
		}
	}

	private void requestDisableMethod(@NonNull MethodChannel.Result result) {
		if (bluetoothAdapter.isEnabled()) {
			bluetoothAdapter.disable();
			result.success(true);
		}
		else {
			result.success(false);
		}
	}

	private void getAddressMethod(@NonNull MethodChannel.Result result) {
		String btAddress = bluetoothAdapter.getAddress();

		if (btAddress.equals("02:00:00:00:00:00")) {
			Log.w(Constants.TAG, "Local Bluetooth MAC address is hidden by system, trying other options...");

			do {
				Log.d(Constants.TAG, "Trying to obtain address using Settings Secure bank");
				try {
					// Requires `LOCAL_MAC_ADDRESS` which could be unavailible for third party applications...
					//old: registrar.activeContext()
					String value = android.provider.Settings.Secure.getString(context.getContentResolver(), "bluetooth_address");
					if (value == null) {
						throw new NullPointerException("null returned, might be no permissions problem");
					}
					btAddress = value;
					break;
				}
				catch (Exception ex) {
					// Ignoring failure (since it isn't critical API for most applications)
					Log.d(Constants.TAG, "Obtaining address using Settings Secure bank failed");
					//result.error("hidden_address", "obtaining address using Settings Secure bank failed", exceptionToString(ex));
				}

				Log.d(Constants.TAG, "Trying to obtain address using reflection against internal Android code");
				try {
					// This will most likely work, but well, it is unsafe
					java.lang.reflect.Field mServiceField;
					mServiceField = bluetoothAdapter.getClass().getDeclaredField("mService");
					mServiceField.setAccessible(true);

					Object bluetoothManagerService = mServiceField.get(bluetoothAdapter);
					if (bluetoothManagerService == null) {
						if (!bluetoothAdapter.isEnabled()) {
							Log.d(Constants.TAG, "Probably failed just because adapter is disabled!");
						}
						throw new NullPointerException();
					}
					java.lang.reflect.Method getAddressMethod;
					getAddressMethod = bluetoothManagerService.getClass().getMethod("getAddress");
					String value = (String) getAddressMethod.invoke(bluetoothManagerService);
					if (value == null) {
						throw new NullPointerException();
					}
					btAddress = value;
					Log.d(Constants.TAG, "Probably succed: " + btAddress + " ✨ :F");
					break;
				}
				catch (Exception ex) {
					// Ignoring failure (since it isn't critical API for most applications)
					Log.d(Constants.TAG, "Obtaining address using reflection against internal Android code failed");
					//result.error("hidden_address", "obtaining address using reflection agains internal Android code failed", exceptionToString(ex));
				}

				Log.d(Constants.TAG, "Trying to look up address by network interfaces - might be invalid on some devices");
				try {
					// This method might return invalid MAC address (since Bluetooth might use other address than WiFi).
					// @TODO . further testing: 1) check is while open connection, 2) check other devices
					Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
					String value = null;
					while (interfaces.hasMoreElements()) {
						NetworkInterface networkInterface = interfaces.nextElement();
						String name = networkInterface.getName();

						if (!name.equalsIgnoreCase("wlan0")) {
							continue;
						}

						byte[] addressBytes = networkInterface.getHardwareAddress();
						if (addressBytes != null) {
							StringBuilder addressBuilder = new StringBuilder(18);
							for (byte b : addressBytes) {
								addressBuilder.append(String.format("%02X:", b));
							}
							addressBuilder.setLength(17);
							value = addressBuilder.toString();
						}
					}
					if (value == null) {
						throw new NullPointerException();
					}
					btAddress = value;
				}
				catch (Exception ex) {
					// Ignoring failure (since it isn't critical API for most applications)
					Log.w(Constants.TAG, "Looking for address by network interfaces failed");
					//result.error("hidden_address", "looking for address by network interfaces failed", exceptionToString(ex));
				}
			} while (false);
		}
		result.success(btAddress);
	}

	private void setNameMethod(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
		if (!call.hasArgument("name")) {
			result.error("invalid_argument", "argument 'name' not found", null);
			return;
		}

		String name;
		try {
			name = call.argument("name");
		}
		catch (ClassCastException ex) {
			result.error("invalid_argument", "'name' argument is required to be string", null);
			return;
		}

		result.success(bluetoothAdapter.setName(name));
	}

	private void getDeviceBondStateMethod(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
		if (!call.hasArgument("address")) {
			result.error("invalid_argument", "argument 'address' not found", null);
			return;
		}

		//check input address if present
		String address;
		try {
			address = call.argument("address");
			if (!BluetoothAdapter.checkBluetoothAddress(address)) {
				throw new ClassCastException();
			}
		}
		catch (ClassCastException ex) {
			result.error("invalid_argument", "'address' argument is required to be string containing remote MAC address", null);
			return;
		}

		result.success(bluetoothAdapter.getRemoteDevice(address).getBondState());
	}

	private void removeDeviceBondMethod(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
		if (!call.hasArgument("address")) {
			result.error("invalid_argument", "argument 'address' not found", null);
			return;
		}

		//check input address if present
		String address;
		try {
			address = call.argument("address");
			if (!BluetoothAdapter.checkBluetoothAddress(address)) {
				throw new ClassCastException();
			}
		}
		catch (ClassCastException ex) {
			result.error("invalid_argument", "'address' argument is required to be string containing remote MAC address", null);
			return;
		}

		BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
		switch (device.getBondState()) {
			case BluetoothDevice.BOND_BONDING:
				result.error("bond_error", "device already bonding", null);
				return; //TODO prima andava in loop sul metodo, capire se aveva senso
			case BluetoothDevice.BOND_NONE:
				result.error("bond_error", "device already unbonded", null);
				return; //TODO prima andava in loop sul metodo, capire se aveva senso
			default:
				// Proceed.
				break;
		}

		try {
			java.lang.reflect.Method method;
			method = device.getClass().getMethod("removeBond");
			boolean value = (Boolean) method.invoke(device);
			result.success(value);
		}
		catch (Exception ex) {
			result.error("bond_error", "error while unbonding", exceptionToString(ex));
		}
	}

	private void bondDeviceMethod(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
		if (!call.hasArgument("address")) {
			result.error("invalid_argument", "argument 'address' not found", null);
			return;
		}

		if (bondStateBroadcastReceiver != null) {
			result.error("bond_error", "another bonding process is ongoing from local device", null);
			return;
		}

		//check input address if present
		String address;
		try {
			address = call.argument("address");
			if (!BluetoothAdapter.checkBluetoothAddress(address)) {
				throw new ClassCastException();
			}
		}
		catch (ClassCastException ex) {
			result.error("invalid_argument", "'address' argument is required to be string containing remote MAC address", null);
			return;
		}

		BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
		switch (device.getBondState()) {
			case BluetoothDevice.BOND_BONDING:
				result.error("bond_error", "device already bonding", null);
				return; //TODO prima andava in loop sul metodo, capire se aveva senso
			case BluetoothDevice.BOND_BONDED:
				result.error("bond_error", "device already bonded", null);
				return; //TODO prima andava in loop sul metodo, capire se aveva senso
			default:
				// Proceed.
				break;
		}

		bondStateBroadcastReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				// @TODO . BluetoothDevice.ACTION_PAIRING_CANCEL
				if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())) {
					final BluetoothDevice someDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
					if (!someDevice.equals(device)) {
						return;
					}

					final int newBondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR);
					switch (newBondState) {
						case BluetoothDevice.BOND_BONDING:
							// Wait for true bond result :F
							return;
						case BluetoothDevice.BOND_BONDED:
							result.success(true);
							break;
						case BluetoothDevice.BOND_NONE:
							result.success(false);
							break;
						default:
							result.error("bond_error", "invalid bond state while bonding", null);
							break;
					}
					//old: registrar.activeContext()
					context.unregisterReceiver(this);
					bondStateBroadcastReceiver = null;
				}
			}
		};

		final IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
		//old: registrar.activeContext()
		context.registerReceiver(bondStateBroadcastReceiver, filter);

		if (!device.createBond()) {
			result.error("bond_error", "error starting bonding process", null);
		}
	}

	private void pairingRequestHandlingEnableMethod(@NonNull MethodChannel.Result result) {
		if (this.isPairingRequestHandlerSet) {
			result.error("logic_error", "pairing request handling is already enabled", null);
			return;
		}
		Log.d(Constants.TAG, "Starting listening for pairing requests to handle");

		this.isPairingRequestHandlerSet = true;
		final IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST);
		//old: registrar.activeContext()
		context.registerReceiver(pairingRequestReceiver, filter);
	}

	private void pairingRequestHandlingDisableMethod() {
		this.isPairingRequestHandlerSet = false;
		try {
			//old: registrar.activeContext()
			context.unregisterReceiver(pairingRequestReceiver);
			Log.d(Constants.TAG, "Stopped listening for pairing requests to handle");
		}
		catch (IllegalArgumentException ex) {
			// Ignore `Receiver not registered` exception
		}
	}

	private void getBondedDevicesMethod(@NonNull MethodChannel.Result result) {
		ensurePermissions(granted -> {
			if (!granted) {
				result.error("no_permissions", "discovering other devices requires location access permission", null);
				return;
			}

			List<Map<String, Object>> list = new ArrayList<>();
			for (BluetoothDevice device1 : bluetoothAdapter.getBondedDevices()) {
				Map<String, Object> entry = new HashMap<>();
				entry.put("address", device1.getAddress());
				entry.put("name", device1.getName());
				entry.put("type", device1.getType());
				entry.put("isConnected", checkIsDeviceConnected(device1));
				entry.put("bondState", BluetoothDevice.BOND_BONDED);
				list.add(entry);
			}

			result.success(list);
		});
	}

	private void startDiscoveryMethod(@NonNull MethodChannel.Result result) {
		ensurePermissions(granted -> {
			if (!granted) {
				result.error("no_permissions", "discovering other devices requires location access permission", null);
				return;
			}

			Log.d(Constants.TAG, "Starting discovery");
			IntentFilter intent = new IntentFilter();
			intent.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
			intent.addAction(BluetoothDevice.ACTION_FOUND);
			//old: registrar.activeContext()
			context.registerReceiver(discoveryReceiver, intent);

			bluetoothAdapter.startDiscovery();

			result.success(null);
		});
	}

	private void cancelDiscoveryMethod(@NonNull MethodChannel.Result result) {
		Log.d(Constants.TAG, "Canceling discovery");
		try {
			//old: registrar.activeContext()
			context.unregisterReceiver(discoveryReceiver);
		}
		catch (IllegalArgumentException ex) {
			// Ignore `Receiver not registered` exception
		}

		bluetoothAdapter.cancelDiscovery();

		if (discoverySink != null) {
			discoverySink.endOfStream();
			discoverySink = null;
		}

		result.success(null);
	}

	private void requestDiscoverableMethod(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
		if(activity!=null) {
			Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);

			if (call.hasArgument("duration")) {
				try {
					int duration = call.argument("duration");
					intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, duration);
				}
				catch (ClassCastException ex) {
					result.error("invalid_argument", "'duration' argument is required to be integer", null);
					return;
				}
			}

			pendingResultForActivityResult = result;
			ActivityCompat.startActivityForResult(activity, intent, Constants.REQUEST_DISCOVERABLE_BLUETOOTH, null);
		}
	}

	private void connectMethod(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
		if (!call.hasArgument("address")) {
			result.error("invalid_argument", "argument 'address' not found", null);
			return;
		}

		//check input address if present
		String address;
		try {
			address = call.argument("address");
			if (!BluetoothAdapter.checkBluetoothAddress(address)) {
				throw new ClassCastException();
			}
		}
		catch (ClassCastException ex) {
			result.error("invalid_argument", "'address' argument is required to be string containing remote MAC address", null);
			return;
		}

		int id = ++lastConnectionId;
		BluetoothConnectionWrapper connection = new BluetoothConnectionWrapper(id, bluetoothAdapter, messenger, () -> connections.remove(id));
		connections.put(id, connection);

		Log.d(Constants.TAG, "Connecting to " + address + " (id: " + id + ")");

		//AsyncTask.execute replaced because of deprecation
		Executors.newSingleThreadExecutor().execute(() -> {
			Handler uiThreadHandler = new Handler(Looper.getMainLooper());
			try {
				connection.connect(address);
//				registrar.activity().runOnUiThread(() -> result.success(id));
				uiThreadHandler.post(() -> result.success(id));
			}
			catch (Exception ex) {
//				registrar.activity().runOnUiThread(() -> result.error("connect_error", ex.getMessage(), exceptionToString(ex)));
				uiThreadHandler.post(() -> result.error("connect_error", ex.getMessage(), exceptionToString(ex)));
				connections.remove(id);
			}
		});
	}

	private void writeMethod(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
		if (!call.hasArgument("id")) {
			result.error("invalid_argument", "argument 'id' not found", null);
			return;
		}

		int id;
		try {
			id = call.argument("id");
		}
		catch (ClassCastException ex) {
			result.error("invalid_argument", "'id' argument is required to be integer id of connection", null);
			return;
		}

		BluetoothConnection connection = connections.get(id);
		if (connection == null) {
			result.error("invalid_argument", "there is no connection with provided id", null);
			return;
		}

		if (call.hasArgument("string")) {
			String string = call.argument("string");
			//AsyncTask.execute replaced because of deprecation
			Executors.newSingleThreadExecutor().execute(() -> {
				Handler uiThreadHandler = new Handler(Looper.getMainLooper());
				try {
					connection.write(string.getBytes());
//					registrar.activity().runOnUiThread(() -> result.success(null));
					uiThreadHandler.post(() -> result.success(null));
				}
				catch (Exception ex) {
//					registrar.activity().runOnUiThread(() -> result.error("write_error", ex.getMessage(), exceptionToString(ex)));
					uiThreadHandler.post(() -> result.error("write_error", ex.getMessage(), exceptionToString(ex)));
				}
			});
		} else if (call.hasArgument("bytes")) {
			byte[] bytes = call.argument("bytes");
			//AsyncTask.execute replaced because of deprecation
			Executors.newSingleThreadExecutor().execute(() -> {
				Handler uiThreadHandler = new Handler(Looper.getMainLooper());
				try {
					connection.write(bytes);
//					registrar.activity().runOnUiThread(() -> result.success(null));
					uiThreadHandler.post(() -> result.success(null));
				}
				catch (Exception ex) {
//					registrar.activity().runOnUiThread(() -> result.error("write_error", ex.getMessage(), exceptionToString(ex)));
					uiThreadHandler.post(() -> result.error("write_error", ex.getMessage(), exceptionToString(ex)));
				}
			});
		}
		else {
			result.error("invalid_argument", "there must be 'string' or 'bytes' argument", null);
		}
	}

	private interface EnsurePermissionsCallback {
		void onResult(boolean granted);
	}

	EnsurePermissionsCallback pendingPermissionsEnsureCallbacks = null;

	private void ensurePermissions(EnsurePermissionsCallback callbacks) {
		if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			if(activity == null) {
				Log.e(Constants.TAG, "Missing permission, cannot use without activity!");
				callbacks.onResult(false);
			}
			ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, Constants.REQUEST_COARSE_LOCATION_PERMISSIONS);

			pendingPermissionsEnsureCallbacks = callbacks;
		} else {
			callbacks.onResult(true);
		}
	}

	/// Helper function to get string out of exception
	private static String exceptionToString(Exception ex) {
		StringWriter sw = new StringWriter();
		PrintWriter pw = new PrintWriter(sw);
		ex.printStackTrace(pw);
		return sw.toString();
	}

	/// Helper function to check is device connected
	private static boolean checkIsDeviceConnected(BluetoothDevice device) {
		try {
			java.lang.reflect.Method method;
			method = device.getClass().getMethod("isConnected");
			return (Boolean) method.invoke(device);
		}
		catch (Exception ex) {
			return false;
		}
	}

	@Override
	public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		if (requestCode == Constants.REQUEST_COARSE_LOCATION_PERMISSIONS) {
			pendingPermissionsEnsureCallbacks.onResult(grantResults[0] == PackageManager.PERMISSION_GRANTED);
			pendingPermissionsEnsureCallbacks = null;
			return true;
		}
		return false;
	}

	@Override
	public boolean onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
			case Constants.REQUEST_ENABLE_BLUETOOTH:
				// @TODO - used underlying value of `Activity.RESULT_CANCELED` since we tend to use `androidx` in which I were not able to find the constant.
				pendingResultForActivityResult.success(resultCode != 0);
				return true;

			case Constants.REQUEST_DISCOVERABLE_BLUETOOTH:
				pendingResultForActivityResult.success(resultCode == 0 ? -1 : resultCode);
				return true;

			default:
				return false;
		}
	}

	@Override
	public boolean onViewDestroy(FlutterNativeView view) {
		onDetachedFromEngine();
		return false; //???
	}

	protected void onDetachedFromEngine() {
		// Unregister all ongoing receivers
		try {
			context.unregisterReceiver(stateReceiver);
		}
		catch (IllegalArgumentException ex) {
			// Ignore `Receiver not registered` exception
		}
		try {
			context.unregisterReceiver(discoveryReceiver);
		}
		catch (IllegalArgumentException ex) {
			// Ignore `Receiver not registered` exception
		}
		try {
			context.unregisterReceiver(pairingRequestReceiver);
		}
		catch (IllegalArgumentException ex) {
			// Ignore `Receiver not registered` exception
		}
		try {
			if (bondStateBroadcastReceiver != null) {
				context.unregisterReceiver(bondStateBroadcastReceiver);
				bondStateBroadcastReceiver = null;
			}
		}
		catch (IllegalArgumentException ex) {
			// Ignore `Receiver not registered` exception
		}
	}

}
