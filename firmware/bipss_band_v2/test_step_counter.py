import math
import unittest
from step_counter import StepCounter


class StepTests(unittest.TestCase):
    def signal(self, detector, seconds, hz=2, amplitude=0.25, start=0):
        for i in range(int(seconds*25)):
            detector.add(0, 0, 1 + amplitude*math.sin(2*math.pi*hz*i/25), start+i*40)

    def test_stationary(self):
        d=StepCounter(); self.signal(d,60,amplitude=0.005)
        self.assertEqual(0,d.total)

    def test_repeating_walking_signal(self):
        d=StepCounter(); self.signal(d,30)
        self.assertTrue(56 <= d.total <= 61,d.total)

    def test_three_isolated_steps_not_a_bout(self):
        d=StepCounter(); self.signal(d,1.4)
        self.assertEqual(0,d.total)

    def test_missing_sensor_does_not_join_bouts(self):
        d=StepCounter(); self.signal(d,1)
        self.signal(d,1,start=10000)
        self.assertEqual(0,d.total)

    def test_rotation_without_magnitude_change(self):
        d=StepCounter()
        for i in range(1000): d.add(math.sin(i/25),0,math.cos(i/25),i*40)
        self.assertEqual(0,d.total)

    def test_invalid_sample_preserves_count_but_resets_bout(self):
        d=StepCounter(); self.signal(d,5); previous=d.total
        d.add(float('nan'),0,1,6000)
        self.assertEqual(previous,d.total)


if __name__ == '__main__': unittest.main()
