/**
 * ============================================================================
 * Project: Smart Ploughing and Cultivation Agriculture Robot
 * Target: Arduino Uno R3 (ATmega328P @ 16 MHz)
 * Description: Production-ready firmware for 4WD differential locomotion,
 *              mechanical plough actuation, seed metering agitation,
 *              and pressurized water/pesticide pump control via Bluetooth SPP.
 * ============================================================================
 * 
 * Hardware Pin Mapping:
 * ----------------------------------------------------------------------------
 * Pin D2  : HC-05 TXD -> Arduino RX (SoftwareSerial)
 * Pin D3  : Arduino TX -> HC-05 RXD (via 1k / 2k ohm voltage divider)
 * Pin D4  : L298N / L293D IN1 (Left Motor Forward)
 * Pin D5  : L298N / L293D IN2 (Left Motor Reverse)
 * Pin D6  : L298N / L293D IN3 (Right Motor Forward)
 * Pin D7  : L298N / L293D IN4 (Right Motor Reverse)
 * Pin D8  : 1-Channel Relay Module (Active-LOW: LOW=ON, HIGH=OFF) -> Water Pump
 * Pin D9  : Seed Dispenser Servo (MG90S PWM)
 * Pin D10 : Plough Lifter Servo (MG996R PWM)
 * Pin D13 : Onboard Status Indicator LED
 * ----------------------------------------------------------------------------
 * Ground Bus: All component grounds (Arduino GND, Driver GND, Battery (-), 
 *             Buck Converter GND, Relay GND, Servo GND) MUST be unified.
 * ============================================================================
 */

#include <SoftwareSerial.h>
#include <Servo.h>

// ============================================================================
// CONFIGURATION & CALIBRATION PARAMETERS
// ============================================================================

// Pin Assignments
const uint8_t PIN_BT_RX       = 2;    // Arduino RX (Connects to HC-05 TX)
const uint8_t PIN_BT_TX       = 3;    // Arduino TX (Connects to HC-05 RX via divider)
const uint8_t PIN_MOTOR_IN1   = 4;    // Left Motors Forward
const uint8_t PIN_MOTOR_IN2   = 5;    // Left Motors Reverse
const uint8_t PIN_MOTOR_IN3   = 6;    // Right Motors Forward
const uint8_t PIN_MOTOR_IN4   = 7;    // Right Motors Reverse
const uint8_t PIN_RELAY_PUMP  = 8;    // Water / Pesticide Pump Relay (Active-LOW)
const uint8_t PIN_SERVO_SEED  = 9;    // Seed Dispenser MG90S Servo
const uint8_t PIN_SERVO_PLOUGH= 10;   // Plough Lifter MG996R Servo
const uint8_t PIN_STATUS_LED  = 13;   // Onboard Status LED

// Timing Parameters (Milliseconds)
const unsigned long WATCHDOG_TIMEOUT_MS    = 2000; // Stop locomotion if no signal within 2s
const unsigned long LED_FLASH_DURATION_MS  = 70;   // Status LED flash length
const unsigned long PLOUGH_TRAVEL_TIME_MS  = 650;  // Time needed for plough to reach position before detaching
const unsigned long SEED_INTERVAL_MS       = 350;  // Agitation period (switching interval between angles)
const unsigned long SEED_CLOSE_TRAVEL_MS   = 300;  // Time allowed to close gate before detaching

// Plough Angle Calibration (Easily adjustable)
const int PLOUGH_RETRACT_ANGLE = 20;   // Transit / Lifted position (degrees)
const int PLOUGH_DEPLOY_ANGLE  = 120;  // Deployed / Ground-engagement position (degrees)

// Seed Dispenser Agitation Angles
const int SEED_CLOSED_ANGLE   = 0;     // Fully shut (prevents seed leakage)
const int SEED_AGITATE_MIN    = 15;    // Agitation stroke lower bound
const int SEED_AGITATE_MAX    = 65;    // Agitation stroke upper bound

// ============================================================================
// SYSTEM OBJECTS & GLOBAL STATE VARIABLES
// ============================================================================

SoftwareSerial btSerial(PIN_BT_RX, PIN_BT_TX); // Communication channel with HC-05
Servo ploughServo;
Servo seedServo;

// Locomotion & Safety Watchdog
bool isMoving = false;
unsigned long lastCommandTimestamp = 0;

// Status LED Non-Blocking Flash State
bool isLedActive = false;
unsigned long ledTurnOffTimestamp = 0;

// Plough Servo Power Management State
bool isPloughActive = false;
unsigned long ploughDetachTimestamp = 0;

// Seed Dispenser Agitation State Machine
bool isSeedingActive = false;
bool isSeedStopping = false;
bool seedToggleState = false;
unsigned long lastSeedAgitateTimestamp = 0;
unsigned long seedDetachTimestamp = 0;

// Pump State (Active-LOW relay)
bool isPumpOn = false;

// ============================================================================
// FUNCTION DECLARATIONS
// ============================================================================
void processCommand(char cmd);
void setLocomotion(uint8_t in1, uint8_t in2, uint8_t in3, uint8_t in4, bool moving);
void stopMotors();
void setPloughPosition(int angle);
void startSeeding();
void stopSeeding();
void setPump(bool state);
void pulseStatusLed();
void updateWatchdog();
void updateServos();
void updateStatusLed();

// ============================================================================
// SETUP ROUTINE
// ============================================================================
void setup() {
  // 1. Initialize Hardware Serial for PC Debugging & SoftwareSerial for Bluetooth
  Serial.begin(9600);
  btSerial.begin(9600);

  // 2. Configure Motor Driver Output Pins
  pinMode(PIN_MOTOR_IN1, OUTPUT);
  pinMode(PIN_MOTOR_IN2, OUTPUT);
  pinMode(PIN_MOTOR_IN3, OUTPUT);
  pinMode(PIN_MOTOR_IN4, OUTPUT);
  stopMotors();

  // 3. Configure Relay Pin (Write HIGH first to prevent active-LOW click at boot)
  digitalWrite(PIN_RELAY_PUMP, HIGH); // Relay OFF (HIGH for active-LOW)
  pinMode(PIN_RELAY_PUMP, OUTPUT);

  // 4. Configure Status LED
  pinMode(PIN_STATUS_LED, OUTPUT);
  digitalWrite(PIN_STATUS_LED, LOW);

  // 5. Initialize Servos to safe default positions and detach to save power
  ploughServo.attach(PIN_SERVO_PLOUGH);
  ploughServo.write(PLOUGH_RETRACT_ANGLE);
  
  seedServo.attach(PIN_SERVO_SEED);
  seedServo.write(SEED_CLOSED_ANGLE);

  // Allow mechanical settlement (blocking allowed ONLY during cold boot setup)
  delay(600);
  ploughServo.detach();
  seedServo.detach();

  // Flash LED 3 times to signal boot readiness
  for (int i = 0; i < 3; i++) {
    digitalWrite(PIN_STATUS_LED, HIGH);
    delay(100);
    digitalWrite(PIN_STATUS_LED, LOW);
    delay(100);
  }

  Serial.println(F("[SYSTEM READY] Smart Agriculture Robot Initialized."));
  Serial.println(F("Listening for HC-05 Bluetooth SPP Commands @ 9600 bps..."));
}

// ============================================================================
// MAIN LOOP (STRICTLY NON-BLOCKING)
// ============================================================================
void loop() {
  // 1. Read Bluetooth SPP incoming commands
  while (btSerial.available() > 0) {
    char cmd = btSerial.read();
    processCommand(cmd);
  }

  // 2. Also accept USB Serial Monitor commands for bench testing without phone
  while (Serial.available() > 0) {
    char cmd = Serial.read();
    // Ignore newline / carriage return characters from terminal
    if (cmd != '\r' && cmd != '\n') {
      processCommand(cmd);
    }
  }

  // 3. Update Non-Blocking Asynchronous Subsystems
  updateWatchdog();
  updateServos();
  updateStatusLed();
}

// ============================================================================
// COMMAND PARSER & DISPATCHER
// ============================================================================
void processCommand(char cmd) {
  // Update watchdog timestamp on any valid recognized command
  lastCommandTimestamp = millis();
  pulseStatusLed();

  Serial.print(F("[CMD RECEIVED]: "));
  Serial.println(cmd);

  switch (cmd) {
    // --- Locomotion Commands ---
    case 'F': // Drive All Wheels Forward
      setLocomotion(HIGH, LOW, HIGH, LOW, true);
      break;

    case 'B': // Drive All Wheels Reverse
      setLocomotion(LOW, HIGH, LOW, HIGH, true);
      break;

    case 'L': // Skid-Steer Left (Left reverse, Right forward)
      setLocomotion(LOW, HIGH, HIGH, LOW, true);
      break;

    case 'R': // Skid-Steer Right (Left forward, Right reverse)
      setLocomotion(HIGH, LOW, LOW, HIGH, true);
      break;

    case 'S': // Emergency / Released Stop
      stopMotors();
      break;

    // --- Tillage (Plough) Commands ---
    case 'P': // Deploy Plough into soil
      setPloughPosition(PLOUGH_DEPLOY_ANGLE);
      break;

    case 'p': // Retract Plough for transit
      setPloughPosition(PLOUGH_RETRACT_ANGLE);
      break;

    // --- Seeding Agitation Commands ---
    case 'D': // Start Seed Metering (Oscillating Dispenser)
      startSeeding();
      break;

    case 'd': // Stop Seed Metering & Close Shutter
      stopSeeding();
      break;

    // --- Irrigation (Water/Pesticide) Commands ---
    case 'W': // Water Pump ON
      setPump(true);
      break;

    case 'w': // Water Pump OFF
      setPump(false);
      break;

    default:
      // Unknown command ignored
      break;
  }
}

// ============================================================================
// LOCOMOTION SUBSYSTEM
// ============================================================================
void setLocomotion(uint8_t in1, uint8_t in2, uint8_t in3, uint8_t in4, bool moving) {
  digitalWrite(PIN_MOTOR_IN1, in1);
  digitalWrite(PIN_MOTOR_IN2, in2);
  digitalWrite(PIN_MOTOR_IN3, in3);
  digitalWrite(PIN_MOTOR_IN4, in4);
  isMoving = moving;
}

void stopMotors() {
  digitalWrite(PIN_MOTOR_IN1, LOW);
  digitalWrite(PIN_MOTOR_IN2, LOW);
  digitalWrite(PIN_MOTOR_IN3, LOW);
  digitalWrite(PIN_MOTOR_IN4, LOW);
  isMoving = false;
}

// ============================================================================
// PLOUGH SUBSYSTEM (MG996R High-Torque Servo)
// ============================================================================
void setPloughPosition(int angle) {
  if (!ploughServo.attached()) {
    ploughServo.attach(PIN_SERVO_PLOUGH);
  }
  ploughServo.write(angle);
  
  // Schedule non-blocking detachment to prevent continuous stall current draw
  isPloughActive = true;
  ploughDetachTimestamp = millis() + PLOUGH_TRAVEL_TIME_MS;
}

// ============================================================================
// SEED DISPENSER SUBSYSTEM (MG90S Agitation State Machine)
// ============================================================================
void startSeeding() {
  if (!isSeedingActive) {
    isSeedingActive = true;
    isSeedStopping = false;
    if (!seedServo.attached()) {
      seedServo.attach(PIN_SERVO_SEED);
    }
    // Start initial agitation stroke immediately
    seedToggleState = true;
    seedServo.write(SEED_AGITATE_MAX);
    lastSeedAgitateTimestamp = millis();
  }
}

void stopSeeding() {
  if (isSeedingActive || seedServo.attached()) {
    isSeedingActive = false;
    isSeedStopping = true;
    
    // Ensure servo is attached to command return to closed zero position
    if (!seedServo.attached()) {
      seedServo.attach(PIN_SERVO_SEED);
    }
    seedServo.write(SEED_CLOSED_ANGLE);
    seedDetachTimestamp = millis() + SEED_CLOSE_TRAVEL_MS;
  }
}

// ============================================================================
// IRRIGATION SUBSYSTEM (Active-LOW Relay Control)
// ============================================================================
void setPump(bool state) {
  isPumpOn = state;
  // Active-LOW relay logic: LOW energizes relay coil, HIGH de-energizes
  if (isPumpOn) {
    digitalWrite(PIN_RELAY_PUMP, LOW);  // PUMP ON
  } else {
    digitalWrite(PIN_RELAY_PUMP, HIGH); // PUMP OFF
  }
}

// ============================================================================
// NON-BLOCKING BACKGROUND TASK HANDLERS
// ============================================================================

// 1. Failsafe Watchdog: Stops motors if Bluetooth link drops during driving
void updateWatchdog() {
  if (isMoving) {
    if (millis() - lastCommandTimestamp >= WATCHDOG_TIMEOUT_MS) {
      stopMotors();
      Serial.println(F("[FAILSAFE WATCHDOG TRIGGERED]: Locomotion timeout. Robot halted."));
    }
  }
}

// 2. Servo Detach & Agitation Manager
void updateServos() {
  unsigned long currentMillis = millis();

  // Handle Plough Servo Auto-Detach
  if (isPloughActive) {
    if (currentMillis >= ploughDetachTimestamp) {
      ploughServo.detach();
      isPloughActive = false;
    }
  }

  // Handle Seed Agitation Oscillation
  if (isSeedingActive) {
    if (currentMillis - lastSeedAgitateTimestamp >= SEED_INTERVAL_MS) {
      lastSeedAgitateTimestamp = currentMillis;
      seedToggleState = !seedToggleState;
      seedServo.write(seedToggleState ? SEED_AGITATE_MAX : SEED_AGITATE_MIN);
    }
  } 
  else if (isSeedStopping) {
    // Handle Seed Servo Detach after reaching closed position
    if (currentMillis >= seedDetachTimestamp) {
      seedServo.detach();
      isSeedStopping = false;
    }
  }
}

// 3. Status LED Pulse Handler
void pulseStatusLed() {
  digitalWrite(PIN_STATUS_LED, HIGH);
  isLedActive = true;
  ledTurnOffTimestamp = millis() + LED_FLASH_DURATION_MS;
}

void updateStatusLed() {
  if (isLedActive && (millis() >= ledTurnOffTimestamp)) {
    digitalWrite(PIN_STATUS_LED, LOW);
    isLedActive = false;
  }
}

