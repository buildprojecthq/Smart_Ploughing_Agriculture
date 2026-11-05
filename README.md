# Smart Ploughing and Cultivation Agriculture Robot

A multi-functional, Bluetooth-controlled agricultural mobile robot designed for precision farming. The system coordinates 4-wheel differential locomotion, mechanical soil ploughing, metered seed dispensing, and pressurized liquid spraying through an Arduino Uno microcontroller and a companion Native Android application.

---

## 1. System Architecture

```
+----------------------------------------------------------------------------------+
|                              COMPANION ANDROID APP                                |
|   [ Connect / Disconnect ]   [ D-Pad Locomotion Pad ]   [ 3-Tool Actuator Toggles]|
+----------------------------------------------------------------------------------+
                                         |
                                (Bluetooth SPP / UART 9600)
                                         v
+----------------------------------------------------------------------------------+
|                            HC-05 BLUETOOTH MODULE                                 |
|                       RX (Pin D3 via Divider) | TX (Pin D2)                      |
+----------------------------------------------------------------------------------+
                                         |
                                         v
+----------------------------------------------------------------------------------+
|                             ARDUINO UNO R3 (ATmega328P)                          |
|             (Non-blocking millis() state machine + 2000ms Watchdog)              |
+----------------------------------------------------------------------------------+
        |                     |                       |                     |
        v                     v                       v                     v
  [ Pins D4-D7 ]          [ Pin D10 ]             [ Pin D9 ]            [ Pin D8 ]
        |                     |                       |                     |
  L298N Motor Driver    MG996R Servo            MG90S Servo         1-Ch Relay (Active-LOW)
        |                     |                       |                     |
 4x Geared DC Motors    Plough Lifter Blade    Seed Dispenser Gate     12V DC Water Pump
 (Differential Drive)   (Deploy / Retract)     (Oscillating Agitator)  (Pesticide / Mist)
```

---

## 2. Directory Structure

```
miniproject_agriculture_bot/
├── firmware/
│   └── firmware.ino                    # Production Arduino Uno C++ firmware
├── android_app/                        # Complete Native Android Studio project
│   ├── app/
│   │   ├── src/main/
│   │   │   ├── AndroidManifest.xml     # SPP & Bluetooth permissions (Android 6 to 14)
│   │   │   ├── java/com/agrirobot/smartplough/
│   │   │   │   ├── MainActivity.kt     # Locomotion & Tool UI controller
│   │   │   │   ├── BluetoothManager.kt # Multi-threaded RFCOMM SPP socket manager
│   │   │   │   └── DeviceListDialog.kt # Bonded device selection modal
│   │   │   └── res/
│   │   │       ├── layout/activity_main.xml
│   │   │       ├── values/{colors,strings,themes}.xml
│   │   │       └── drawable/*.xml
│   │   └── build.gradle.kts
│   ├── build.gradle.kts
│   └── settings.gradle.kts
└── docs/
    └── WIRING_AND_SCHEMATICS.md        # Pinout table, voltage divider, and power rails
```

---

## 3. Communication Protocol (Single-Byte ASCII)

| Byte | Subsystem | Action Performed | Hardware State |
| :---: | :--- | :--- | :--- |
| `'F'` | Locomotion | Forward Drive | Left FWD, Right FWD |
| `'B'` | Locomotion | Reverse Drive | Left REV, Right REV |
| `'L'` | Locomotion | Skid-steer Turn Left | Left REV, Right FWD |
| `'R'` | Locomotion | Skid-steer Turn Right | Left FWD, Right REV |
| `'S'` | Locomotion | Emergency / Neutral Stop | All motor inputs LOW |
| `'P'` | Tillage | Deploy Plough into Soil | Servo D10 rotates to $120^\circ$ and detaches |
| `'p'` | Tillage | Retract Plough for Transit | Servo D10 rotates to $20^\circ$ and detaches |
| `'D'` | Seeding | Start Seed Metering | Servo D9 oscillates $15^\circ \leftrightarrow 65^\circ$ every 350 ms |
| `'d'` | Seeding | Stop Seed Metering | Servo D9 moves to $0^\circ$ (closed) and detaches |
| `'W'` | Irrigation | Spray Pump ON | Pin D8 driven LOW (Relay coil energized) |
| `'w'` | Irrigation | Spray Pump OFF | Pin D8 driven HIGH (Relay de-energized) |

---

## 4. Key Firmware Highlights

1. **Zero Blocking Delays:**
   * Uses non-blocking `millis()` timing exclusively inside `loop()`. The processor continuously checks incoming serial packets while simultaneously oscillating the seed gate and running timeouts.
2. **2000 ms Failsafe Watchdog:**
   * If the mobile device goes out of range or disconnects while the robot is driving, the watchdog timer halts the motors after 2 seconds to prevent runaway hazards.
3. **Brownout & Servo Protection:**
   * High-torque servos detach PWM signals (`servo.detach()`) once the target angle is reached, eliminating continuous stall current draw and preventing brownout resets on the ATmega328P.
4. **Benchtop Serial Mirroring:**
   * Accepts commands from both the HC-05 Bluetooth module and the USB Arduino Serial Monitor (`9600 bps`) for testing without a phone.

---

## 5. Deployment Guide

### Flashing the Arduino Uno:
1. Open [firmware.ino](file:///home/yashas/Projects/miniproject_agriculture_bot/firmware/firmware.ino) in the Arduino IDE.
2. Select Board: **Arduino Uno** and your USB Port.
3. Click **Upload**.
4. Open the Serial Monitor at **9600 baud**. You can send `'F'`, `'S'`, `'P'`, `'p'`, `'D'`, `'d'`, `'W'`, `'w'` directly from your keyboard to test the hardware immediately!

### Building the Android App:
1. Open **Android Studio**.
2. Select **Open an Existing Project** and browse to `/home/yashas/Projects/miniproject_agriculture_bot/android_app`.
3. Connect your Android smartphone via USB (with Developer Mode & USB Debugging enabled).
4. Click **Run 'app'** (Green play icon) or select **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. In your phone's Android Bluetooth Settings, pair with your `HC-05` module (default PIN is `1234` or `0000`).
6. Launch **Smart Agri Robot**, tap **Connect BT**, select `HC-05`, and start driving!

---

## 6. Viva & Presentation Questions Reference

* **Q: Why is an LM2596 buck converter chosen over a 7805 regulator?**
  * *A:* The MG996R servo draws up to $2.5\text{ A}$ stall current. A 7805 linear regulator is rated for only $1.0\text{ A}$ and converts excess voltage into severe heat $(12\text{V} - 5\text{V}) \times 1\text{A} = 7\text{W}$, leading to thermal throttling and Arduino brownout. The LM2596 switching regulator provides up to $3.0\text{ A}$ continuous with $>90\%$ efficiency.
* **Q: Why is a voltage divider required between Arduino TX and HC-05 RX?**
  * *A:* The HC-05 core operates at $3.3\text{V}$ logic. Arduino Uno digital outputs operate at $5.0\text{V}$. A $1\text{ k}\Omega$ and $2\text{ k}\Omega$ divider steps $5.0\text{V}$ down to $3.33\text{V}$, preventing damage to the HC-05 receiver.
* **Q: Why does the seed dispenser servo oscillate instead of holding open?**
  * *A:* Seeds in a funnel hopper clump due to friction and moisture. Static opening causes either complete blockage or an uncontrollable spill. Periodic agitation ($15^\circ \leftrightarrow 65^\circ$) meters the flow uniformly.

