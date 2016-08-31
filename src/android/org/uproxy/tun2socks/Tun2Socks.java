package org.uproxy.tun2socks;

import android.annotation.TargetApi;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.VpnService;
import android.os.Build;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;

public class Tun2Socks extends CordovaPlugin {

  private static final String LOG_TAG = "Tun2Socks";
  private static final String START_ACTION = "start";
  private static final String STOP_ACTION = "stop";
  private static final String ON_DISCONNECT_ACTION = "onDisconnect";
  private static final String DEVICE_SUPPORTS_PLUGIN_ACTION = "deviceSupportsPlugin";
  private static final int REQUEST_CODE_PREPARE_VPN = 100;
  // Standard activity result: operation succeeded.
  public static final int RESULT_OK = -1;

  private String m_socksServerAddress;
  private CallbackContext m_onDisconnectCallback = null;
  private NetworkManager m_networkManager = null;

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext)
      throws JSONException {
    if (!hasVpnService()) {
      callbackContext.error("Device does not support plugin.");
      return false;
    }

    if (action.equals(START_ACTION)) {
      if (args.length() < 1) {
        callbackContext.error("Missing socks server address argument");
      } else {
        // Set instance variable in case we need to start the tunnel vpn service
        // from onActivityResult.
        m_socksServerAddress = args.getString(0);
        Log.i(LOG_TAG, "Got socks server address: " + m_socksServerAddress);
        prepareAndStartTunnelService(callbackContext);
      }
      return true;
    } else if (action.equals(STOP_ACTION)) {
      stopTunnelService();
      callbackContext.success("Stopped tun2socks.");
      return true;
    } else if (action.equals(ON_DISCONNECT_ACTION)) {
      m_onDisconnectCallback = callbackContext;
      return true;
    } else if (action.equals(DEVICE_SUPPORTS_PLUGIN_ACTION)) {
      callbackContext.sendPluginResult(
          new PluginResult(PluginResult.Status.OK, hasVpnService()));
      return true;
    }
    return false;
  }

  // Initializes the plugin.
  // Requires API 23 (Marshmallow) to use the NetworkManager.
  @TargetApi(Build.VERSION_CODES.M)
  @Override
  protected void pluginInitialize() {
    if (!hasVpnService()) {
      Log.i(LOG_TAG, "Device does not support plugin.");
      return;
    }
    m_networkManager = new NetworkManager(getBaseContext());

    LocalBroadcastManager.getInstance(getBaseContext())
        .registerReceiver(
            m_disconnectBroadcastReceiver,
            new IntentFilter(TunnelVpnService.TUNNEL_VPN_DISCONNECT_BROADCAST));
  }

  @Override
  public void onDestroy() {
    // Stop network manager
    if (m_networkManager != null) {
      m_networkManager.destroy();
    }
    // Stop tunnel service in case the user has quit the app without
    // disconnecting the VPN.
    stopTunnelService();
  }

  protected void prepareAndStartTunnelService(CallbackContext callbackContext) {
    Log.d(LOG_TAG, "Starting tun2socks...");
    if (hasVpnService()) {
      if (prepareVpnService()) {
        startTunnelService(getBaseContext());
      }
      callbackContext.success("Started tun2socks");
    } else {
      Log.e(LOG_TAG, "Device does not support whole device VPN mode.");
      callbackContext.error("Failed to start tun2socks");
    }
  }

  // Returns whether the device supports the tunnel VPN service.
  private boolean hasVpnService() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M;
  }

  @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
  protected boolean prepareVpnService() throws ActivityNotFoundException {
    // VpnService: need to display OS user warning. If whole device
    // option is selected and we expect to use VpnService, so show the prompt
    // in the UI before starting the service.
    Intent prepareVpnIntent = VpnService.prepare(getBaseContext());
    if (prepareVpnIntent != null) {
      Log.d(LOG_TAG, "prepare vpn with activity");
      this.cordova.setActivityResultCallback(Tun2Socks.this);
      this.cordova.getActivity().startActivityForResult(
          prepareVpnIntent, REQUEST_CODE_PREPARE_VPN);
      return false;
    }
    return true;
  }

  @Override
  public void onActivityResult(int request, int result, Intent data) {
    if (request == REQUEST_CODE_PREPARE_VPN && result == RESULT_OK) {
      startTunnelService(getBaseContext());
    }
  }

  protected void startTunnelService(Context context) {
    Log.i(LOG_TAG, "starting tunnel service");
    if (isServiceRunning()) {
      Log.w(LOG_TAG, "already running service");
      TunnelManager tunnelManager = TunnelState.getTunnelState().getTunnelManager();
      if (tunnelManager != null) {
        tunnelManager.restartTunnel(m_socksServerAddress);
      }
      return;
    }
    Intent startTunnelVpn = new Intent(context, TunnelVpnService.class);
    startTunnelVpn.putExtra(TunnelManager.SOCKS_SERVER_ADDRESS_EXTRA, m_socksServerAddress);
    if (this.cordova.getActivity().startService(startTunnelVpn) == null) {
      Log.d(LOG_TAG, "failed to start tunnel vpn service");
      return;
    }
    TunnelState.getTunnelState().setStartingTunnelManager();
  }

  protected boolean isServiceRunning() {
    TunnelState tunnelState = TunnelState.getTunnelState();
    return tunnelState.getStartingTunnelManager() || tunnelState.getTunnelManager() != null;
  }

  private void stopTunnelService() {
    // Use signalStopService to asynchronously stop the service.
    // 1. VpnService doesn't respond to stopService calls
    // 2. The UI will not block while waiting for stopService to return
    // This scheme assumes that the UI will monitor that the service is
    // running while the Activity is not bound to it. This is the state
    // while the tunnel is shutting down.
    Log.i(LOG_TAG, "stopping tunnel service");
    TunnelManager currentTunnelManager = TunnelState.getTunnelState().getTunnelManager();
    if (currentTunnelManager != null) {
      currentTunnelManager.signalStopService();
    }
  }

  private Context getBaseContext() {
    return this.cordova.getActivity().getApplicationContext();
  }

  public void onDisconnect() {
    if (m_onDisconnectCallback != null) {
      PluginResult result = new PluginResult(PluginResult.Status.OK);
      result.setKeepCallback(true);
      m_onDisconnectCallback.sendPluginResult(result);
    }
  }

  private DisconnectBroadcastReceiver m_disconnectBroadcastReceiver =
      new DisconnectBroadcastReceiver(Tun2Socks.this);

  private class DisconnectBroadcastReceiver extends BroadcastReceiver {
    private Tun2Socks m_handler;

    public DisconnectBroadcastReceiver(Tun2Socks handler) {
      m_handler = handler;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
      // Callback into handler so we can communicate the disconnect event to js.
      m_handler.onDisconnect();
    }
  };
}
