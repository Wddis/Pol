"""Two-pass LINEAR loudness normalisation (no pumping / «давится»), video stream copied.
usage: python3 norm_audio.py in.mp4 out.mp4 [--i -14] [--tp -1.5]
Pass 1 measures the whole file, pass 2 applies ONE constant gain + look-ahead peak limiter, output 48 kHz.
Why (16.11.2026): single-pass loudnorm is dynamic — it rides the gain up and down within seconds, so loud reactions
get squashed and quiet parts swell; without -ar it also upsampled to 96 kHz. Used by shorts_render2.sh and edit2.py."""
import sys, json, subprocess, argparse, re
ap = argparse.ArgumentParser(); ap.add_argument("inp"); ap.add_argument("out")
ap.add_argument("--i", type=float, default=-14); ap.add_argument("--tp", type=float, default=-1.5); ap.add_argument("--lra", type=float, default=20)
x = ap.parse_args()
base = f"loudnorm=I={x.i}:TP={x.tp}:LRA={x.lra}"
r = subprocess.run(["ffmpeg", "-hide_banner", "-nostats", "-i", x.inp, "-vn", "-af", base + ":print_format=json", "-f", "null", "-"],
                   capture_output=True, text=True)
m = json.loads(re.findall(r"\{[^{}]*\}", r.stderr)[-1])
# loudnorm silently falls back to DYNAMIC mode when linear gain would push true peak over TP (sources at 0 dBTP do) ->
# constant gain (volume) + transparent look-ahead peak limiter instead: one level for the whole file, only rare peaks touched
gain = x.i - float(m["input_i"])
lim = 10 ** (x.tp / 20)
af = f"volume={gain:.2f}dB,alimiter=limit={lim:.3f}:attack=5:release=50:level=false,aresample=48000"
subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", x.inp, "-map", "0:v?", "-map", "0:a", "-c:v", "copy", "-af", af,
                "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-movflags", "+faststart", x.out], check=True)
print(f"norm: in {m['input_i']} LUFS tp {m['input_tp']} -> gain {gain:+.2f} dB + peak limiter {x.tp} dB", flush=True)
