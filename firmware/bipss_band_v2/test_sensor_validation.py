import unittest
from sensor_validation import crc8, decode_aht21, ens_ready, ens_compensation, pulse_is_fresh

class SensorValidationTest(unittest.TestCase):
    def test_reference_crc(self):
        self.assertEqual(0xf7, crc8(b'123456789'))

    def test_valid_measurement_and_corruption(self):
        # 50% RH, 25 C: the two 20-bit raw values share byte 3.
        data = bytearray((0x08, 0x80, 0x00, 0x06, 0x00, 0x00))
        data.append(crc8(data))
        self.assertEqual((25.0, 50.0), decode_aht21(data))
        data[2] ^= 1
        self.assertIsNone(decode_aht21(data))

    def test_busy_uncalibrated_short_frame(self):
        for flag in [0x88, 0]:
            data = bytearray((flag, 0x80, 0, 6, 0, 0));data.append(crc8(data))
            self.assertIsNone(decode_aht21(data))
        self.assertIsNone(decode_aht21(bytes(6)))

    def test_ens_rejects_warmup_error_and_no_new_sample(self):
        self.assertTrue(ens_ready(0x82))
        for status in [0x02, 0xc2, 0x86, 0x8a, 0x8e, 0x80]:
            self.assertFalse(ens_ready(status))

    def test_compensation_reference_and_invalid_values(self):
        self.assertEqual(bytes((0x13, 0x8a, 0x4a, 0, 0x64)), ens_compensation(25, 50))
        for t,h in [(float('nan'), 50), (25,float('inf')), (25,101), (-100,50)]:
            self.assertIsNone(ens_compensation(t,h))

    def test_stalled_sample_clock_does_not_keep_vitals_fresh(self):
        self.assertTrue(pulse_is_fresh(1500, 1400, 1000, 800))
        self.assertFalse(pulse_is_fresh(6500, 1400, 1000, 800))
        self.assertFalse(pulse_is_fresh(1500, 1400, 9000, 800))
        self.assertFalse(pulse_is_fresh(1500, -1, 1000, 800))

if __name__ == '__main__': unittest.main()
