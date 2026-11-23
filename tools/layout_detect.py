"""Pick the webcam/screen crops for shorts from a stream frame (no AI quota needed).
Finds the streamer's face (OpenCV YuNet, assets/models/face_yunet.onnx) in the bottom-right part of a 1920x1080 frame and picks the known layout
whose webcam window contains the face best; prints JSON {"layout":…, "CAM":"w:h:x:y", "SCR":"w:h:x:y", "face":[x,y,w,h]}.
usage: python3 layout_detect.py frame.png   (or a video: python3 layout_detect.py video.mp4 [sec])"""
import sys, json, subprocess, os, tempfile
import cv2
LAYOUTS = {   # measured by hand on T2x2 streams (window edges, docs/REGLAMENT.md п.3)
    "default":        {"win": (1298, 712, 622, 368), "CAM": "440:285:1300:745", "SCR": "1264:711:328:0"},
    "game_small_cam": {"win": (1398, 662, 522, 418), "CAM": "396:272:1404:676", "SCR": "1060:596:338:230"},
}
def frame(path, t):
    if path.lower().endswith((".png", ".jpg", ".jpeg")): return cv2.imread(path)
    p = os.path.join(tempfile.mkdtemp(), "f.png")
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-ss", str(t), "-i", path, "-frames:v", "1", p], check=True); return cv2.imread(p)
MODEL = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "assets", "models", "face_yunet.onnx")   # OpenCV Zoo YuNet (MIT)
def detect(img):
    H, W = img.shape[:2]
    det = cv2.FaceDetectorYN.create(MODEL, "", (W, H), 0.7, 0.3, 50); _, res = det.detect(img)
    faces = [tuple(int(v) for v in f[:4]) for f in (res if res is not None else []) if f[0] > 1100 and f[1] > 450]   # streamer cam = bottom-right
    if not len(faces): return {"layout": "default", **{k: LAYOUTS["default"][k] for k in ("CAM", "SCR")}, "face": None}
    fx, fy, fw, fh = max(faces, key=lambda f: f[2] * f[3]); cx, cy = fx + fw / 2, fy + fh / 2
    def score(L):
        x, y, w, h = L["win"]
        inside = x <= fx and y <= fy and fx + fw <= x + w and fy + fh <= y + h
        return (inside, -abs(cx - (x + w / 2)) - abs(cy - (y + h / 2)))
    name = max(LAYOUTS, key=lambda n: score(LAYOUTS[n]))
    return {"layout": name, "CAM": LAYOUTS[name]["CAM"], "SCR": LAYOUTS[name]["SCR"], "face": [int(fx), int(fy), int(fw), int(fh)]}
if __name__ == "__main__":
    print(json.dumps(detect(frame(sys.argv[1], float(sys.argv[2]) if len(sys.argv) > 2 else 30))))
