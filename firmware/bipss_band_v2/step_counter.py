"""MPU6050 wrist step candidate detector; no phone sensor or PPG required.

25 Hz input in g. Cadence gate + four consecutive excursions reject isolated
gestures; arm-only periodic movement can still fool a wrist accelerometer.
Thresholds require physical validation on the final enclosure/placement.
"""
import math


class StepCounter:
    def __init__(self):
        self.total = 0
        self.gravity = None
        self.smooth = 0.0
        self.last_t = None
        self.last_peak = None
        self.armed = True
        self.run = 0

    def add(self, x, y, z, t_ms):
        mag = math.sqrt(x*x + y*y + z*z)
        if not 0.2 <= mag <= 3.4:
            self.reset_window()
            return self.total
        if self.last_t is None or not 0 < t_ms - self.last_t <= 160:
            self.reset_window()
            self.gravity = mag
        self.last_t = t_ms
        self.gravity += 0.04 * (mag - self.gravity)
        self.smooth += 0.45 * ((mag - self.gravity) - self.smooth)
        if self.smooth < 0.025:
            self.armed = True
        if self.armed and self.smooth >= 0.10:
            self.armed = False
            interval = None if self.last_peak is None else t_ms - self.last_peak
            if interval is not None and interval < 280:
                return self.total
            if interval is None or interval > 1500:
                self.run = 1
            else:
                self.run += 1
                if self.run == 4:
                    self.total = (self.total + 4) & 0xffffffff
                elif self.run > 4:
                    self.total = (self.total + 1) & 0xffffffff
            self.last_peak = t_ms
        return self.total

    def reset_window(self):
        self.gravity = None
        self.smooth = 0.0
        self.last_t = None
        self.last_peak = None
        self.armed = True
        self.run = 0
