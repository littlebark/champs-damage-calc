"""对照脚本：用与 Android 端相同的模型检查 det/rec 预处理是否一致。

用法：python tools/paddle_check.py <图片路径>
输出：det 概率图统计、若干检测框、按框识别的文本。
"""
import sys
import os
import numpy as np
import cv2
import onnxruntime as ort

MODEL_DIR = os.path.join(os.environ.get("LOCALAPPDATA", ""), "Temp", "ocrmodels")
DET = os.path.join(MODEL_DIR, "PP-OCRv6_det_small.onnx")
REC = os.path.join(MODEL_DIR, "PP-OCRv6_rec_small.onnx")
KEYS = r"C:\code-hub\champs-damage-calc\android\android-app\app\src\main\assets\ocr\keys.txt"


def load_keys():
    with open(KEYS, "r", encoding="utf-8") as handle:
        return [line.rstrip("\n") for line in handle]


def det_preprocess(image, limit=1280):
    h, w = image.shape[:2]
    ratio = min(1.0, limit / max(h, w))
    rw = max(32, int(round(w * ratio / 32)) * 32)
    rh = max(32, int(round(h * ratio / 32)) * 32)
    resized = cv2.resize(image, (rw, rh))
    mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
    std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
    img = (resized.astype(np.float32) / 255.0 - mean) / std
    img = img.transpose(2, 0, 1)[None]
    return img.astype(np.float32), rw, rh, rw / w, rh / h


def run_det(session, tensor):
    name = session.get_inputs()[0].name
    out = session.run(None, {name: tensor})[0]
    return out[0][0]


def rec_one(session, crop, keys):
    h, w = crop.shape[:2]
    target_h = 48
    target_w = max(16, int(round(w * target_h / h)))
    target_w = min(target_w, 1600)
    resized = cv2.resize(crop, (target_w, target_h))
    img = (resized.astype(np.float32) / 255.0 - 0.5) / 0.5
    img = img.transpose(2, 0, 1)[None]
    name = session.get_inputs()[0].name
    out = session.run(None, {name: img.astype(np.float32)})[0]
    return out


def ctc_decode(out, keys):
    logits = out[0]
    idx = logits.argmax(axis=1)
    text = []
    prev = -1
    scores = []
    for step, best in enumerate(idx):
        if best != 0 and best != prev:
            text.append(keys[best - 1] if best - 1 < len(keys) else "?")
            scores.append(float(logits[step][best]))
        prev = int(best)
    conf = sum(scores) / len(scores) if scores else 0.0
    return "".join(text), conf, logits.shape


def main():
    path = sys.argv[1]
    keys = load_keys()
    det = ort.InferenceSession(DET)
    rec = ort.InferenceSession(REC)
    image = cv2.imread(path)
    print("image", image.shape, "keys", len(keys))
    tensor, rw, rh, sx, sy = det_preprocess(image)
    prob = run_det(det, tensor)
    print("det map", prob.shape, "min", float(prob.min()), "max", float(prob.max()), "mean", float(prob.mean()))
    mask = (prob > 0.3).astype(np.uint8)
    contours, _ = cv2.findContours(mask, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
    boxes = []
    for contour in contours:
        rect = cv2.minAreaRect(contour)
        box = cv2.boxPoints(rect)
        score = float(cv2.mean(prob, mask)[0]) if False else 0.0
        w = max(box[:, 0]) - min(box[:, 0])
        h = max(box[:, 1]) - min(box[:, 1])
        if min(w, h) < 4:
            continue
        boxes.append((min(box[:, 0]), min(box[:, 1]), max(box[:, 0]), max(box[:, 1])))
    print("contours", len(boxes))
    boxes.sort(key=lambda b: (b[1], b[0]))
    for index, box in enumerate(boxes[:8]):
        left = int(max(0, box[0] / sx))
        top = int(max(0, box[1] / sy))
        right = int(min(image.shape[1], box[2] / sx))
        bottom = int(min(image.shape[0], box[3] / sy))
        if right - left < 8 or bottom - top < 8:
            continue
        crop = image[top:bottom, left:right]
        out = rec_one(rec, crop, keys)
        text, conf, shape = ctc_decode(out, keys)
        print(f"box={left},{top},{right},{bottom} crop={crop.shape[1]}x{crop.shape[0]} shape={shape} conf={conf:.2f} text={text!r}")

    # 手动指定的裁剪框（与手机端一致），用于逐个比对
    for rect in [tuple(int(v) for v in arg.split(",")) for arg in sys.argv[2:]]:
        left, top, right, bottom = rect
        crop = image[top:bottom, left:right]
        out = rec_one(rec, crop, keys)
        text, conf, shape = ctc_decode(out, keys)
        print(f"manual box={left},{top},{right},{bottom} crop={crop.shape[1]}x{crop.shape[0]} shape={shape} conf={conf:.2f} text={text!r}")


if __name__ == "__main__":
    main()
