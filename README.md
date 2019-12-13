# Wireless Software Synchronization of Multiple Distributed Cameras

Reference code for the paper
[Wireless Software Synchronization of Multiple Distributed Cameras](https://arxiv.org/abs/1812.09366).
_Sameer Ansari, Neal Wadhwa, Rahul Garg, Jiawen Chen_, ICCP 2019.

If you use this code, please cite our paper:

```
@article{AnsariSoftwareSyncICCP2019,
  author  = {Ansari, Sameer and Wadhwa, Neal and Garg, Rahul and Chen, Jiawen},
  title   = {Wireless Software Synchronization of Multiple Distributed Cameras},
  journal = {ICCP},
  year    = {2019},
}
```

_This is not an officially supported Google product._

![Five smartphones synchronously capture a balloon filled with red water being popped.](https://i.imgur.com/rCkC5jW.gif)
_Five smartphones synchronously capture a balloon filled with red water being popped to within 250 μs timing accuracy._

## Android App to Capture Synchronized Images

The app has been tested on the Google Pixel 2, 3, and 4.
It may work on other Android phones with minor changes.

Note: On Pixel 1 devices the viewfinder frame rate drops after a couple
captures, which will likely cause time synchronization to be much
lower in accuracy. This may be due to thermal throttling.
Disabling saving to JPEG or lowering the frame rate may help.

### Installation instructions:

1.  Download [Android Studio](https://developer.android.com/studio). When you
    install it, make sure to also install the Android SDK API 27.
2.  Click "Open an existing Android Studio project". Select the "CaptureSync"
    directory.
3.  There will be a pop-up with the title "Gradle Sync" complaining about a
    missing file called gradle-wrapper.properties. Click ok to recreate the
    Gradle wrapper.
4.  Plug in your Pixel smartphone. You will need to enable USB debugging. See
    https://developer.android.com/studio/debug/dev-options for further
    instructions.
5.  Go to the "Run" menu at the top and click "Run 'app'" to compile and install
    the app.

Note: By default, the app will likely start in client mode, with no UI options.

#### Setting up the Leader device

1.  On the system pulldown menu of the leader device, disable WiFi.
2.  [Start a hotspot](https://support.google.com/android/answer/9059108).
3.  After this, opening the app on the leader device should show UI options, as
    well as which clients are connected.

#### Setting up the Client(s) device

1.  Enable WiFi and connect to the leader's hotspot.
2.  As client devices on the network start up, they will sync up with the
    leader, which will show up on both the leader and client UIs.
3.  (Optional) Go to wifi preferences and disable "Turn on Wi-Fi automatically"
    and "Connect to open networks", this will keep devices from automatically
    disconnecting from a hotspot without internet.

#### Capturing images

1.  (Optional) Press the phase align button to have each device synchronize
    their phase, the phase error will show in real-time.
2.  (Optional) Move the exposure and sensitivity slider on the leader device to
    manually set 2A values.
3.  Press the `Capture Still` button to request a synchronized image slightly in
    the future on all devices.

This will save to internal storage, as well as show up under the Pictures
directory in the photo gallery.

Note: Fine-tuning the phase configuration JSON parameters in the `raw` resources
directory will let you trade alignment-time for phase alignment accuracy.

Note: AWB is used for simplicity, but could also be synchronized with devices.

### Information about saved data

Synchronized images are saved to the external files directory for this app,
which is:

```
/storage/emulated/0/Android/data/com.googleresearch.capturesync/files
```

A JPEG version of the image will also populate in the photo gallery under the
`Pictures` subdirectory under `Settings -> Device Folders`.

Pulling data from individual phones using:

```
adb pull /storage/emulated/0/Android/data/com.googleresearch.capturesync/files /tmp/outputdir
```

The images are also stored as a raw YUV file (in
[packed NV21 format](https://wiki.videolan.org/YUV)) and a metadata file which
can be converted to PNG or JPG using the Python script in the `scripts/`
directory.

#### Example Workflow

1. User sets up all devices on the same hotspot WiFi network of leader device.
2. User starts app on all devices, uses exposure sliders and presses the
`Phase Align` button on the leader device.
3. User presses capture button on the leader device to collect captures.
4. If JPEG is enabled (default) the user can verify captures by going to the
`Pictures` photo directory on their phone through Google Photos or similar.
5. After a capture session, the user pulls the data from each phone to the local
machine using `adb pull`.
6. (Optional)The python script is used to convert the raw images using:
```
python3 yuv2rgb.py img_<timestamp>.nv21 nv21_metadata_<timestamp>.txt
out.<png|jpg>.
```

## How Networking and Communications work

Note: Algorithm specifics can be found in our paper linked at the top.

Leader and clients use heartbeats to connect with one another and keep track of
state. Simple NTP is used for clock synchronization. That, phase alignment and
2A is used to make phones capture the same type of image as the same time.
Capturing is done by sending a trigger time to all devices which will
independently capture at that time.

All of this requires communication. One component of this library is to provide
a method for sending messages (RPCs) between the leader device and client
devices, to allow for  synchronization as well as capture triggering, AWB,
state etc.

The network uses wifi with UDP messages for communication. The leader IP is
determined automatically by client devices.

A message is sent as an RPC byte sequence consisting of an integer method ID
(defined in
[`SyncConstants.java`](app/src/main/java/com/googleresearch/capturesync/softwaresync/SyncConstants.java)
and the string message payload. (defined in
[`SoftwareSyncBase.java`](app/src/main/java/com/googleresearch/capturesync/softwaresync/SoftwareSyncBase.java)
`sendRpc()`)

Note: This app has the leader set up a hotspot, through this client devices can
automatically determine the leader IP address from the connection, however one
could manually configure IP address with a different network configuration, such
as using a router that all the phones connect to.


### Capture

The leader sends a `METHOD_SET_TRIGGER_TIME` RPC (Method id located in
[`SoftwareSyncController.java`](app/src/main/java/com/googleresearch/capturesync/SoftwareSyncController.java)
) to all the clients containing a requested capture
synchronized timestamp far enough in the future to account for potential network
latency between devices. In practice network latency between devices is ~100ms
or less, however the latency may be more or less depending on what devices or
network configuration is used.

Note: In this case the future is 500ms, giving plenty of time for network
latency.

Each client and leader receives the future timestamp and `CameraController.java`
checks the timestamp of each frame as it comes in and pulls the closest frame at
or past the desired timestamp and saves it to disk. One advantage of this method
is that if any delays happen in capturing, the synchronized capture timestamp
will show that the time offset between images without requiring looking at the
images.

Note: Zero-shutter-lag capture is possible if each device is capable of storing
frames in a ring buffer. Then when a desired current/past capture timestamp is
provided each device can check in the ring buffer for the closest frame
timestamp and save that one.

### Heartbeat

A leader listens for a heartbeat from any client, to determine if a client
exists and whether starting the synchronization with that client is necessary.
When it gets a heartbeat from a client that is not synchronized, it initiates an
NTP handshake with the client to determine the clock offsets between the two
devices

A client continuously sends out `METHOD_HEARTBEAT` RPC to the leader with it's
current boolean state for if it's already synchronized with the leader.

A leader received `METHOD_HEARTBEAT` and responds with a `METHOD_HEARTBEAT_ACK`
to the client. The leader uses this to keep track of a list of clients using the
`ClientInfo` object for each client, which will also include sync information.

The client waits for a `METHOD_OFFSET_UPDATE` from the leader which contains the
time offset needed to get to a synchronized clock domain with the leader, after
which it's heartbeat messages will show that it is synced to the leader.

Whenever a client gets desynchronized, the heartbeats will notify the leader of
it and they will re-initiate synchronization. Through this mechanism automated
clock synchronization and maintenance is achieved.

### Simple NTP Handshake

The
[`SimpleNetworkTimeProtocol.java`](app/src/main/java/com/googleresearch/capturesync/softwaresync/SimpleNetworkTimeProtocol.java)
is used to perform an NTP handshake between the leader and client. The local
time domain of the devices is used, using the
[`Ticker.java`](app/src/main/java/com/googleresearch/capturesync/softwaresync/Ticker.java)
method for getting local nanosecond time.

An NTP handshake consists of the leader sending a message containing the current
leader timestamp t0. The client receives and appends it's receiving local
timestamp t1, as well as the timestamp it sends a return message to the leader
t2. The leader receives this at timestamp t3, and using these 4 times estimates
the clock offset between the two devices, accounting for network latency.

This result is encapsulated in
[`SntpOffsetResponse.java`](app/src/main/java/com/googleresearch/capturesync/softwaresync/SntpOffsetResponse.java)
which also contains the hard upper bound timing error on the offset. In practice
the timing error is an order of magnitude smaller since wifi network
communication is mostly symmetric with the bias accounted for by choosing the
smallest sample(s).

More information can be found in our paper on this topic.

### Phase Alignment

The leader sends out a `METHOD_DO_PHASE_ALIGN` RPC (Method id located in
`SoftwareSyncController.java`) to all the clients whenever the Align button is
pressed. Each client on receipt then starts a phase alignment process (handled
by `PhaseAlignController.java`) which may take a couple frames to settle.

Note: The leader could instead send its current phase to all devices, and the
devices could align to that, reducing the total potential error. For simplicity
this app uses a hard-coded goal phase.

### Exposure / White Balance / Focus

For simplicity, this app uses manual exposure, hard-coded white balance, and
auto-focus. The leader uses UI sliders to set exposure and sensitivity, which
automatically sends out a `METHOD_SET_2A` RPC (Method id located in
[`SoftwareSyncController.java`](app/src/main/java/com/googleresearch/capturesync/SoftwareSyncController.java)
) to all the clients, which update their 2A as
well. Technically 2A is a misnomer here as it is only setting exposure and
sensitivity, not white balance.

It is possible to use auto exposure/sensitivity and white balance, and have the
leader lock and send the current 2A using the same RPC mechanism to other
devices which can then set theirs manually to the same.

Note: One could try synchronizing focus values as well, though in practice we
found the values were not accurate enough to provide sharp focus across devices.
Hence we keep auto-focus.
