"""Toon Studio render server (runs free on Google Colab / Kaggle GPU).

Colab cells:
  !pip -q install flask diffusers transformers accelerate kokoro soundfile moviepy==1.0.3 scipy
  !apt-get -qq install -y espeak-ng ffmpeg
  !wget -q https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 -O cf && chmod +x cf
  !nohup ./cf tunnel --url http://localhost:8000 > cf.log 2>&1 &
  !grep -o 'https://[a-z0-9-]*\\.trycloudflare.com' cf.log | head -1   # <- paste in the app
  %run server.py
"""
import os, threading, uuid, traceback
import numpy as np, soundfile as sf, torch, scipy.io.wavfile as wav
from flask import Flask, request, jsonify, send_file
from diffusers import AutoPipelineForText2Image
from kokoro import KPipeline
from transformers import pipeline as hfpipe
from moviepy.editor import (ImageClip, AudioFileClip, CompositeAudioClip,
                            concatenate_videoclips, concatenate_audioclips)

OUT = "/content/out" if os.path.isdir("/content") else "./out"
os.makedirs(OUT, exist_ok=True)
dev = "cuda" if torch.cuda.is_available() else "cpu"

img_pipe = AutoPipelineForText2Image.from_pretrained(
    "stabilityai/sdxl-turbo", torch_dtype=torch.float16 if dev == "cuda" else torch.float32).to(dev)
tts_it = KPipeline(lang_code="i")
tts_en = KPipeline(lang_code="a")
music_pipe = hfpipe("text-to-audio", "facebook/musicgen-small", device=0 if dev == "cuda" else -1)

VOICES = {"it": {"narrator": "im_nicola", "child": "if_sara", "female": "if_sara", "male": "im_nicola"},
          "en": {"narrator": "am_adam", "child": "af_bella", "female": "af_heart", "male": "am_michael"}}
W, H, FPS = 1280, 720, 24
jobs = {}
app = Flask(__name__)


def speak(text, voice, lang, path):
    tts = tts_it if lang == "it" else tts_en
    chunks = [a for _, _, a in tts(text, voice=VOICES[lang].get(voice, VOICES[lang]["narrator"]))]
    audio = np.concatenate(chunks) if chunks else np.zeros(2400, dtype="float32")
    sf.write(path, audio, 24000)
    return len(audio) / 24000


def ken_burns(img, dur, mode):
    clip = ImageClip(np.array(img)).set_duration(dur)
    z = lambda t: 1 + 0.12 * (t / dur)
    if mode == "zoom_out":
        z = lambda t: 1.12 - 0.12 * (t / dur)
    clip = clip.resize(z)
    if mode in ("pan_left", "pan_right"):
        clip = clip.resize(1.15)
        x0 = clip.w - W
        clip = clip.set_position(lambda t: (-(x0 * t / dur) if mode == "pan_right" else -(x0 * (1 - t / dur)), "center"))
        from moviepy.editor import CompositeVideoClip
        return CompositeVideoClip([clip], size=(W, H)).set_duration(dur)
    from moviepy.editor import CompositeVideoClip
    return CompositeVideoClip([clip.set_position("center")], size=(W, H)).set_duration(dur)


def run(job, req):
    st = jobs[job]
    try:
        look, script = req["look"], req["script"]
        lang = "it" if req.get("lang") == "Italiano" else "en"
        scenes = script["scenes"]
        clips = []
        for i, sc in enumerate(scenes):
            st["progress"] = f"{i + 1}/{len(scenes)}"
            prompt = f"3D animated Pixar-style cartoon, {look}, {sc['visual']}, vibrant colors, soft lighting, high quality render"
            img = img_pipe(prompt, num_inference_steps=2, guidance_scale=0.0, width=768, height=432).images[0].resize((W, H))
            ap = f"{OUT}/{job}_{i}.wav"
            dur = max(speak(sc["line"], sc.get("voice", "narrator"), lang, ap) + 1.0, 5.0)
            clip = ken_burns(img, dur, sc.get("camera", "zoom_in")).set_audio(AudioFileClip(ap))
            clips.append(clip.crossfadein(0.4))
        video = concatenate_videoclips(clips, method="compose", padding=-0.4)

        st["progress"] = "musica"
        m = music_pipe(script.get("music", "cheerful children cartoon theme"), forward_params={"max_new_tokens": 1500})
        mp = f"{OUT}/{job}_music.wav"
        wav.write(mp, rate=m["sampling_rate"], data=m["audio"].squeeze())
        bg = AudioFileClip(mp).volumex(0.18)
        loops = int(video.duration // bg.duration) + 1
        bg = concatenate_audioclips([bg] * loops).subclip(0, video.duration)
        video = video.set_audio(CompositeAudioClip([video.audio, bg]))

        st["progress"] = "encoding"
        path = f"{OUT}/{job}.mp4"
        video.write_videofile(path, fps=FPS, codec="libx264", audio_codec="aac", preset="veryfast", logger=None)
        st.update(state="done", path=path)
    except Exception as e:
        traceback.print_exc()
        st.update(state="error", error=str(e))


@app.post("/render")
def render():
    job = uuid.uuid4().hex[:10]
    jobs[job] = {"state": "running", "progress": "0"}
    threading.Thread(target=run, args=(job, request.json), daemon=True).start()
    return jsonify(job=job)


@app.get("/status/<job>")
def status(job):
    s = jobs.get(job, {"state": "error", "error": "job sconosciuto"})
    return jsonify({k: v for k, v in s.items() if k != "path"})


@app.get("/video/<job>")
def video(job):
    return send_file(jobs[job]["path"], mimetype="video/mp4", conditional=True)


@app.after_request
def cors(r):
    r.headers["Access-Control-Allow-Origin"] = "*"
    return r


app.run(host="0.0.0.0", port=8000)
