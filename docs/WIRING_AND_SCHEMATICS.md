# Hardware Wiring & Schematic Guide
## Smart Ploughing and Cultivation Agriculture Robot

This document provides the complete, pin-by-pin interconnection matrix, voltage divider schematic, and power distribution architecture.

---

## 1. Master Pin Allocation Matrix

| Arduino Uno Pin | Component / Subsystem | Pin on Module | Signal Direction | Electrical / Logic Level |
| :--- | :--- | :--- | :--- | :--- |
| **D2** | HC-05 Bluetooth Module | **TXD** | Input to Arduino | 3.3V TTL (Meets Uno 5V TTL high threshold $\ge 3.0\text{V}$) |
| **D3** | HC-05 Bluetooth Module | **RXD** | Output from Arduino | **5V stepped down to 3.3V** via 1kΩ/2kΩ divider |
| **D4** | L298N / L293D Motor Driver | **IN1** | Output | 5V TTL Logic (Left Motors Forward) |
| **D5** | L298N / L293D Motor Driver | **IN2** | Output | 5V TTL Logic (Left Motors Reverse) |
| **D6** | L298N / L293D Motor Driver | **IN3** | Output | 5V TTL Logic (Right Motors Forward) |
| **D7** | L298N / L293D Motor Driver | **IN4** | Output | 5V TTL Logic (Right Motors Reverse) |
| **D8** | 1-Channel Relay Module | **IN** | Output | Active-LOW trigger (LOW = Pump ON, HIGH = Pump OFF) |
| **D9** | Seed Metering Servo (MG90S) | **Signal (Orange/Yellow)**| Output | 50 Hz Hardware PWM; VCC from LM2596 5V rail |
| **D10** | Plough Lifter Servo (MG996R) | **Signal (Orange/White)**| Output | 50 Hz Hardware PWM; VCC from LM2596 5V rail |
| **D13** | Status Indicator LED | Onboard LED | Output | Pulses for 70 ms on valid Bluetooth command |
| **GND** | Common Ground Bus | GND | Power | Unified Ground for all system modules |
| **5V / Vin** | LM2596 Buck Converter | **OUT (+)** | Power Input | Regulated 5.0V DC (3A max) |

---

## 2. Power Distribution Architecture

```
                       [ 12V 3S Li-ion Battery Pack / 11.1V - 12.6V ]
                                     |
                +--------------------+--------------------+
                | (+) 12V                                 | (-) GND (BAT-)
                v                                         v
   +-------------------------+               +--------------------------+
   |   L298N Motor Driver    |               |  LM2596 Buck Converter   |
   |   12V Power Terminal    |               |  IN (+)         IN (-)   |
   +-------------------------+               +--------------------------+
                |                                         |
                | (Drives 4x 12V DC Motors)               | Regulates down to 5.0V DC (3A)
                |                                         v
                |                            +--------------------------+
                |                            |  OUT (+)        OUT (-)  |
                |                            +--------------------------+
                |                                 |             |
                |                                 |             +============+
                |                                 v                          |
                |                         +---------------+                  |
                |                         |  5.0V Bus (+) |                  |
                |                         +---------------+                  |
                |                            |   |   |   |                   |
                |  +-------------------------+   |   |   |                   |
                |  | Arduino 5V Pin              |   |   |                   |
                |  | Relay VCC Pin               |   |   |                   |
                |  | MG996R Plough Servo VCC ----+   |   |                   |
                |  | MG90S Seed Servo VCC -----------+   |                   |
                |  | HC-05 VCC Pin ----------------------+                   |
                |                                                            |
                +============================================================+
                                              |
                                    [ COMMON GROUND BUS ]
                        (Battery (-), LM2596 IN/OUT (-), Arduino GND,
                         L298N Logic GND, Relay GND, Servos GND, HC-05 GND)
```

> [!IMPORTANT]
> **Why LM2596 instead of 7805?**
> The high-torque MG996R plough servo draws up to **$2.5\text{ A}$ stall current** when lifting against soil friction. An older 7805 regulator is limited to $1.0\text{ A}$ and dissipates $(12\text{V} - 5\text{V}) \times 1\text{A} = 7\text{ Watts}$ of pure heat, causing immediate thermal shutdown and Arduino brownout resets. The LM2596 switching regulator provides up to **3.0A continuous** at over 90% efficiency without overheating.

---

## 3. HC-05 UART Voltage Divider

The HC-05 Bluetooth module has a $3.3\text{V}$ RX pin. Sending a $5\text{V}$ logic signal from Arduino pin `D3` directly into the HC-05 RX pin can degrade or damage the Bluetooth module over time.

Use two resistors to form a voltage divider:
* $R_1 = 1\text{ k}\Omega$ (between Arduino `D3` and HC-05 `RXD`)
* $R_2 = 2\text{ k}\Omega$ (between HC-05 `RXD` and `GND`)

$$V_{\text{out}} = V_{\text{in}} \times \frac{R_2}{R_1 + R_2} = 5.0\text{ V} \times \frac{2000}{1000 + 2000} \approx 3.33\text{ V}$$

```
   Arduino Pin D3 (TX) ----[ 1 kΩ ]----+----> HC-05 RXD Pin
                                       |
                                    [ 2 kΩ ]
                                       |
                                      GND
```

*Note:* Arduino `D2` (RX) connects directly to HC-05 `TXD`. The HC-05 transmits at $3.3\text{V}$, which is recognized as logic HIGH by the ATmega328P ($V_{IH} \ge 3.0\text{V}$).

---

## 4. L298N / L293D Motor Driver Wiring

* **Left Motors:** 2x 12V Geared Motors wired in parallel to OUT1 & OUT2.
* **Right Motors:** 2x 12V Geared Motors wired in parallel to OUT3 & OUT4.
* **ENA & ENB Jumpers:** Keep the default onboard jumpers installed on ENA and ENB (full 100% duty cycle speed).

### Direction Truth Table:
| Operation | Command | IN1 (D4) | IN2 (D5) | IN3 (D6) | IN4 (D7) | Left Side | Right Side |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **Forward** | `'F'` | HIGH | LOW | HIGH | LOW | FWD | FWD |
| **Reverse** | `'B'` | LOW | HIGH | LOW | HIGH | REV | REV |
| **Skid Left** | `'L'` | LOW | HIGH | HIGH | LOW | REV | FWD |
| **Skid Right** | `'R'` | HIGH | LOW | LOW | HIGH | FWD | REV |
| **Hard Stop** | `'S'` | LOW | LOW | LOW | LOW | STOP | STOP |

---

## 5. Water Pump & 1-Channel Relay Interfacing

* **Relay Type:** Optocoupler-isolated 5V 1-Channel Relay (Active-LOW).
* **Connections:**
  * Relay `VCC` -> LM2596 `+5V`
  * Relay `GND` -> Common `GND`
  * Relay `IN` -> Arduino Pin `D8`
* **Load Circuit:**
  * 12V Battery `(+)` -> Relay `COM` (Common terminal)
  * Relay `NO` (Normally Open) -> DC Water Pump `(+)`
  * DC Water Pump `(-)` -> Common `GND`
* **Software Safety:** The firmware initializes pin `D8` to `HIGH` before declaring it `OUTPUT` to prevent momentary relay clicks during boot.

---

## 6. Servos Interfacing (Plough & Seed Dispenser)

| Servo Function | Model | Control Pin | Power (VCC) | Ground |
| :--- | :--- | :--- | :--- | :--- |
| **Plough Lifter** | TowerPro MG996R (Metal Gear) | Pin `D10` | LM2596 `+5V` rail | Common `GND` |
| **Seed Dispenser** | TowerPro MG90S (Micro Metal) | Pin `D9` | LM2596 `+5V` rail | Common `GND` |

> [!CAUTION]
> **NEVER connect the servo red VCC wire to the Arduino Uno 5V onboard pin!**
> The Uno's onboard regulator can only output $\approx 400\text{--}500\text{ mA}$. When an MG996R moves, it pulls up to $1.5\text{ A}$, which will cause the Arduino to reset continuously. Connect servo VCC directly to the output of the LM2596 buck converter.

