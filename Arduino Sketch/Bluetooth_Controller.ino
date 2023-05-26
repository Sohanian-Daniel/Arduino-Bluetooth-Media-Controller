#include <PinChangeInterrupt.h>
#include <PinChangeInterruptBoards.h>
#include <PinChangeInterruptPins.h>
#include <PinChangeInterruptSettings.h>

#define VOLUME A0
#define PAUSE 5
#define SEEK 6
#define NEXT 7
#define PREVIOUS 8

#define DEBOUNCE_TIME 200

// lit = last interrupt time
static unsigned long pause_lit = 0;
static unsigned long seek_lit = 0;
static unsigned long next_lit = 0;
static unsigned long previous_lit = 0; 
static unsigned int last_volume = 0;

void debounceButton(unsigned long &last_interrupt_time, String message) {
  unsigned long interrupt_time = millis();
  // If interrupts come faster than DEBOUNCE_TIME, assume it's a bounce and ignore
  if (interrupt_time - last_interrupt_time > DEBOUNCE_TIME) {
    Serial.print(message);
  }
  last_interrupt_time = interrupt_time;
}

void sendPause() {
  debounceButton(pause_lit, "P!");
}

void sendForward() {
  debounceButton(seek_lit, "F!");
}

void sendNext() {
  debounceButton(next_lit, "N!");
}

void sendPrevious() {
  debounceButton(previous_lit, "B!");
}

// Timer set up for 100 ms
void initTimer() {
  noInterrupts();
  
  TCCR1A = 0; 
  TCCR1B = 0;
  TCNT1 = 0;
  OCR1A = 1562;
  
  TCCR1B |= (1 << WGM12);
  TCCR1B |= (1 << CS12) | (1 << CS10);
  TIMSK1 |= (1 << OCIE1A);
  
  interrupts();
}

ISR(TIMER1_COMPA_vect) {
  // Values from 0 - 1023
  unsigned int sensorValue = analogRead(A0);
  // Divided by 10, 0 - 102
  sensorValue /= 10;
  // If bigger than 102, set to 99 
  // (quirk of how I parse and send volume, phones interpret this to 100)
  sensorValue = (sensorValue > 99) ? 99 : sensorValue;

  if (sensorValue != last_volume) {
    Serial.println("!" + String(sensorValue));
  }

  last_volume = sensorValue;
}

void setup() {
  // Bluetooth module works on UART Serial connection
  // I didn't use SoftwareSerial because of an issue with PinChangeInterrupt
  Serial.begin(9600);
  
  initTimer();

  pinMode(PAUSE, INPUT_PULLUP);
  pinMode(SEEK, INPUT_PULLUP);
  pinMode(NEXT, INPUT_PULLUP);
  pinMode(PREVIOUS, INPUT_PULLUP);
  
  // I use the PinChangeInterrupt library to make my life easier setting up these interrupts
  // The alternative was to mess around with the PCINT vector and figure out which pin called
  // the interrupt, I opted to do it simpler (but slower) using 3rd party libraries.
  attachPCINT(digitalPinToPCINT(PAUSE), sendPause, FALLING);
  attachPCINT(digitalPinToPCINT(SEEK), sendForward, FALLING);
  attachPCINT(digitalPinToPCINT(NEXT), sendNext, FALLING);
  attachPCINT(digitalPinToPCINT(PREVIOUS), sendPrevious, FALLING);
}

void loop() {

}
