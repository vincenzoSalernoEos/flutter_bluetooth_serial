package io.github.edufolly.flutterbluetoothserial;

import android.content.Context;

import androidx.annotation.NonNull;
import io.flutter.embedding.engine.FlutterEngine;
import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.PluginRegistry.Registrar;

public class FlutterBluetoothSerialPlugin implements FlutterPlugin, ActivityAware {

	//handler con tutte le logiche del plugin
	private FlutterBluetoothSerialCallHandler callHandler;

	// channels (non possono essere final perché vengono dichiarati post costruttore per mancanza del messenger)
	private MethodChannel methodChannel;
	private EventChannel stateChannel;
	private EventChannel discoveryChannel;

	/**
	 * This {@code FlutterPlugin} has been associated with a {@link FlutterEngine} instance.
	 *
	 * <p>Relevant resources that this {@code FlutterPlugin} may need are provided via the {@code
	 * binding}. The {@code binding} may be cached and referenced until {@link
	 * #onDetachedFromEngine(FlutterPluginBinding)} is invoked and returns.
	 */
	@Override
	public void onAttachedToEngine(@NonNull FlutterPluginBinding binding) {
		genericStartup(binding.getBinaryMessenger(), binding.getApplicationContext());
	}

	/// Registers plugin in Flutter plugin system
	public static void registerWith(Registrar registrar) {
		FlutterBluetoothSerialPlugin instance = new FlutterBluetoothSerialPlugin();
		instance.genericStartup(registrar.messenger(), registrar.context());

		registrar.addRequestPermissionsResultListener(instance.callHandler);
		registrar.addActivityResultListener(instance.callHandler);
		registrar.addViewDestroyListener(instance.callHandler);
	}

	public void genericStartup(BinaryMessenger messenger, Context context) {
		//creazione channel statici
		methodChannel		= new MethodChannel	(messenger, Constants.METHODS_CHANNEL	);
		stateChannel		= new EventChannel	(messenger, Constants.STATE_CHANNEL		);
		discoveryChannel	= new EventChannel	(messenger, Constants.DISCOVERY_CHANNEL	);

		callHandler = new FlutterBluetoothSerialCallHandler(messenger, context, methodChannel);

		//dichiarazione handler channel statici
		methodChannel		.setMethodCallHandler	(callHandler);
		stateChannel		.setStreamHandler		(callHandler.getStateStreamHandler());
		discoveryChannel	.setStreamHandler		(callHandler.getDiscoveryStreamHandler());
	}

	/**
	 * This {@code FlutterPlugin} has been removed from a {@link FlutterEngine} instance.
	 *
	 * <p>The {@code binding} passed to this method is the same instance that was passed in {@link
	 * #onAttachedToEngine(FlutterPluginBinding)}. It is provided again in this method as a
	 * convenience. The {@code binding} may be referenced during the execution of this method, but it
	 * must not be cached or referenced after this method returns.
	 *
	 * <p>{@code FlutterPlugin}s should release all resources in this method.
	 */
	@Override
	public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
		methodChannel		.setMethodCallHandler(null);
		stateChannel		.setStreamHandler(null);
		discoveryChannel	.setStreamHandler(null);

		for (BluetoothConnectionWrapper btConnection : callHandler.connections.values()) {
			btConnection.readChannel.setStreamHandler(null);
		}

		callHandler.onDetachedFromEngine();
	}

	//-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------

	/**
	 * This {@code ActivityAware} {@link FlutterPlugin} is now associated with an {@link android.app.Activity}.
	 *
	 * <p>This method can be invoked in 1 of 2 situations:
	 *
	 * <ul>
	 *   <li>This {@code ActivityAware} {@link FlutterPlugin} was just added to a {@link
	 *       FlutterEngine} that was already connected to a running {@link android.app.Activity}.
	 *   <li>This {@code ActivityAware} {@link FlutterPlugin} was already added to a {@link
	 *       FlutterEngine} and that {@link FlutterEngine} was just connected to an {@link android.app.Activity}.
	 * </ul>
	 * <p>
	 * The given {@link ActivityPluginBinding} contains {@link android.app.Activity}-related references that an
	 * {@code ActivityAware} {@link FlutterPlugin} may require, such as a reference to the actual
	 * {@link android.app.Activity} in question. The {@link ActivityPluginBinding} may be referenced until either
	 * {@link #onDetachedFromActivityForConfigChanges()} or {@link #onDetachedFromActivity()} is
	 * invoked. At the conclusion of either of those methods, the binding is no longer valid. Clear
	 * any references to the binding or its resources, and do not invoke any further methods on the
	 * binding or its resources.
	 */
	@Override
	public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
		callHandler.setActivity(binding.getActivity());

		//V2 method
		binding.addRequestPermissionsResultListener	(callHandler);
		binding.addActivityResultListener			(callHandler);
	}

	/**
	 * The {@link android.app.Activity} that was attached and made available in {@link
	 * #onAttachedToActivity(ActivityPluginBinding)} has been detached from this {@code
	 * ActivityAware}'s {@link FlutterEngine} for the purpose of processing a configuration change.
	 *
	 * <p>By the end of this method, the {@link android.app.Activity} that was made available in {@link
	 * #onAttachedToActivity(ActivityPluginBinding)} is no longer valid. Any references to the
	 * associated {@link android.app.Activity} or {@link ActivityPluginBinding} should be cleared.
	 *
	 * <p>This method should be quickly followed by {@link
	 * #onReattachedToActivityForConfigChanges(ActivityPluginBinding)}, which signifies that a new
	 * {@link android.app.Activity} has been created with the new configuration options. That method provides a
	 * new {@link ActivityPluginBinding}, which references the newly created and associated {@link
	 * android.app.Activity}.
	 *
	 * <p>Any {@code Lifecycle} listeners that were registered in {@link
	 * #onAttachedToActivity(ActivityPluginBinding)} should be deregistered here to avoid a possible
	 * memory leak and other side effects.
	 */
	@Override
	public void onDetachedFromActivityForConfigChanges() {
		callHandler.setActivity(null);
	}

	/**
	 * This plugin and its {@link FlutterEngine} have been re-attached to an {@link android.app.Activity} after
	 * the {@link android.app.Activity} was recreated to handle configuration changes.
	 *
	 * <p>{@code binding} includes a reference to the new instance of the {@link android.app.Activity}. {@code
	 * binding} and its references may be cached and used from now until either {@link
	 * #onDetachedFromActivityForConfigChanges()} or {@link #onDetachedFromActivity()} is invoked. At
	 * the conclusion of either of those methods, the binding is no longer valid. Clear any references
	 * to the binding or its resources, and do not invoke any further methods on the binding or its
	 * resources.
	 */
	@Override
	public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
		callHandler.setActivity(binding.getActivity());

		//V2 method
		binding.addRequestPermissionsResultListener	(callHandler);
		binding.addActivityResultListener			(callHandler);
	}

	/**
	 * This plugin has been detached from an {@link android.app.Activity}.
	 *
	 * <p>Detachment can occur for a number of reasons.
	 *
	 * <ul>
	 *   <li>The app is no longer visible and the {@link android.app.Activity} instance has been destroyed.
	 *   <li>The {@link FlutterEngine} that this plugin is connected to has been detached from its
	 *       {@link io.flutter.embedding.android.FlutterView}.
	 *   <li>This {@code ActivityAware} plugin has been removed from its {@link FlutterEngine}.
	 * </ul>
	 * <p>
	 * By the end of this method, the {@link android.app.Activity} that was made available in {@link
	 * #onAttachedToActivity(ActivityPluginBinding)} is no longer valid. Any references to the
	 * associated {@link android.app.Activity} or {@link ActivityPluginBinding} should be cleared.
	 *
	 * <p>Any {@code Lifecycle} listeners that were registered in {@link
	 * #onAttachedToActivity(ActivityPluginBinding)} or {@link
	 * #onReattachedToActivityForConfigChanges(ActivityPluginBinding)} should be deregistered here to
	 * avoid a possible memory leak and other side effects.
	 */
	@Override
	public void onDetachedFromActivity() {
		callHandler.setActivity(null);
	}

}
