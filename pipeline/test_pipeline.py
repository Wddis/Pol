import unittest
from pipeline import source_windows, format_profile, chapter_times

class PipelineTests(unittest.TestCase):
    def test_single_coordinate_system(self):
        p = {"keep": [[10, 20], [25, 40]], "intro_seconds": 3}
        self.assertEqual(source_windows(p,[{"start":18,"end":27}])[0]["end"],13)
        self.assertEqual(source_windows(p,[{"start":18,"end":27}])[1]["start"],13)
        self.assertEqual(chapter_times(p, [[28,"Вторая глава"]], "Старт"), [(0,"Старт"),(16,"Вторая глава")])

    def test_no_upscale_or_duplicate_frames(self):
        self.assertEqual(format_profile({"width":1280,"height":720,"avg_frame_rate":"30000/1001"}),(1280,720,"30000/1001"))

if __name__=="__main__":
    unittest.main()
