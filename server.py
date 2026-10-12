"""
Local AI Voice Conversion Server
Pipeline: Demucs (tach vocal) -> RVC (doi giong) -> (tuy chon) doi tempo -> tron lai mp3
Toi uu cho GPU 6GB VRAM (RTX 3060): chay tuan tu, giai phong VRAM giua cac buoc.

Cau truc thu muc:
    main.py
    models/
        ten_giong.pth        <- model RVC (bat buoc)
        ten_giong.index      <- file index cung ten (khong bat buoc, giup giong giong hon)
"""
import gc
import shutil
import subprocess
import sys
import threading
import traceback
import uuid
from pathlib import Path

from fastapi import BackgroundTasks, FastAPI, File, Form, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydub import AudioSegment

BASE = Path(__file__).parent.resolve()
UPLOAD_DIR = BASE / "uploads"
OUTPUT_DIR = BASE / "outputs"
MODEL_DIR = BASE / "models"
for d in (UPLOAD_DIR, OUTPUT_DIR, MODEL_DIR):
    d.mkdir(exist_ok=True)

ALLOWED_EXT = {".mp3", ".wav", ".flac", ".m4a", ".ogg"}
MAX_UPLOAD_MB = 100

app = FastAPI(title="Local Voice Conversion Server")

# Trang thai job (trong bo nho) + khoa GPU: moi lan chi 1 job dung GPU
JOBS: dict[str, dict] = {}
GPU_LOCK = threading.Lock()


# --------------------------------------------------------------------------- #
# Tien ich
# --------------------------------------------------------------------------- #
def run_cmd(cmd: list[str]) -> None:
    """Chay lenh ngoai, khong dung shell (an toan, khong command injection)."""
    result = subprocess.run(cmd, capture_output=True, text=True)
    if result.returncode != 0:
        tail = (result.stderr or result.stdout or "")[-1500:]
        raise RuntimeError(f"Lenh that bai: {' '.join(cmd[:4])}...\n{tail}")


def free_gpu() -> None:
    gc.collect()
    try:
        import torch

        if torch.cuda.is_available():
            torch.cuda.empty_cache()
    except Exception:
        pass


def set_status(job_id: str, status: str, step: str = "", **extra) -> None:
    JOBS[job_id].update(status=status, step=step, **extra)


def time_stretch(src: Path, dst: Path, rate: float) -> None:
    """Doi tempo, giu nguyen cao do. atempo chi nhan 0.5 - 2.0."""
    run_cmd(["ffmpeg", "-y", "-i", str(src), "-filter:a", f"atempo={rate}", str(dst)])


def find_model(name: str) -> tuple[Path, Path | None]:
    pth = MODEL_DIR / f"{Path(name).stem}.pth"  # Path(...).stem chan path traversal
    if not pth.is_file():
        raise RuntimeError(f"Khong tim thay model: {pth.name}")
    index = pth.with_suffix(".index")
    return pth, (index if index.is_file() else None)


# --------------------------------------------------------------------------- #
# Cac buoc cua pipeline
# --------------------------------------------------------------------------- #
def step_separate(input_path: Path, job_dir: Path) -> tuple[Path, Path]:
    """Buoc 1: Demucs tach vocal / nhac nen (chay tien trinh rieng -> tra het VRAM khi xong)."""
    run_cmd([
        sys.executable, "-m", "demucs",
        "-n", "htdemucs",
        "--two-stems=vocals",
        "-d", "cuda",
        "--segment", "7",      # chia doan nho de tiet kiem VRAM (6GB an toan)
        "-j", "1",
        "-o", str(job_dir),
        str(input_path),
    ])
    sep = job_dir / "htdemucs" / input_path.stem
    vocal, inst = sep / "vocals.wav", sep / "no_vocals.wav"
    if not vocal.exists() or not inst.exists():
        raise RuntimeError("Demucs khong tao ra vocals.wav / no_vocals.wav")
    return vocal, inst


def step_convert_voice(vocal: Path, out: Path, model_name: str, pitch: int,
                       index_rate: float, f0_method: str) -> None:
    """Buoc 2: RVC doi giong. Nap model -> infer -> xoa khoi VRAM."""
    from rvc_python.infer import RVCInference

    pth, index = find_model(model_name)
    rvc = RVCInference(device="cuda:0")
    try:
        rvc.load_model(str(pth), index_path=str(index) if index else "")
        rvc.set_params(
            f0method=f0_method,      # rmvpe: chat luong tot nhat, nhe
            f0up_key=pitch,          # ban nhac nam->nu: +12, nu->nam: -12, cung gioi: 0
            index_rate=index_rate if index else 0.0,
            filter_radius=3,
            rms_mix_rate=0.25,
            protect=0.33,
        )
        rvc.infer_file(str(vocal), str(out))
    finally:
        del rvc
        free_gpu()


def step_mix(vocal: Path, inst: Path, out: Path, tempo: float, vocal_gain_db: float) -> None:
    """Buoc 3+4: (tuy chon) doi tempo CA HAI track cung ti le, roi tron thanh mp3."""
    if abs(tempo - 1.0) > 1e-3:
        v2, i2 = vocal.with_name("vocal_tempo.wav"), inst.with_name("inst_tempo.wav")
        time_stretch(vocal, v2, tempo)
        time_stretch(inst, i2, tempo)
        vocal, inst = v2, i2

    v = AudioSegment.from_file(vocal) + vocal_gain_db
    i = AudioSegment.from_file(inst)
    mix = i.overlay(v)
    mix.export(out, format="mp3", bitrate="256k")


# --------------------------------------------------------------------------- #
# Worker nen
# --------------------------------------------------------------------------- #
def process_job(job_id: str, input_path: Path, model_name: str, pitch: int,
                index_rate: float, f0_method: str, tempo: float, vocal_gain_db: float) -> None:
    job_dir = OUTPUT_DIR / job_id
    set_status(job_id, "queued", "Dang cho GPU...")
    with GPU_LOCK:
        try:
            find_model(model_name)  # kiem tra som

            set_status(job_id, "running", "1/3 Tach vocal bang Demucs")
            vocal, inst = step_separate(input_path, job_dir)

            set_status(job_id, "running", "2/3 Doi giong bang RVC")
            converted = job_dir / "converted_vocals.wav"
            step_convert_voice(vocal, converted, model_name, pitch, index_rate, f0_method)

            set_status(job_id, "running", "3/3 Tron bai hat")
            final = job_dir / "final_mix.mp3"
            step_mix(converted, inst, final, tempo, vocal_gain_db)

            # Don file tam, chi giu ban cuoi + vocal da doi giong
            shutil.rmtree(job_dir / "htdemucs", ignore_errors=True)
            input_path.unlink(missing_ok=True)

            set_status(job_id, "done", "Hoan tat", download_url=f"/download/{job_id}")
        except Exception as e:
            traceback.print_exc()
            set_status(job_id, "error", "That bai", error=str(e))
        finally:
            free_gpu()


# --------------------------------------------------------------------------- #
# API
# --------------------------------------------------------------------------- #
@app.get("/")
def home():
    return {"message": "Voice Conversion Server dang chay", "docs": "/docs"}


@app.get("/models")
def list_models():
    return {"models": sorted(p.stem for p in MODEL_DIR.glob("*.pth"))}


@app.get("/gpu")
def gpu_info():
    try:
        import torch

        if not torch.cuda.is_available():
            return {"cuda": False}
        free, total = torch.cuda.mem_get_info()
        return {
            "cuda": True,
            "name": torch.cuda.get_device_name(0),
            "vram_free_mb": free // 2**20,
            "vram_total_mb": total // 2**20,
        }
    except Exception as e:
        return {"cuda": False, "error": str(e)}


@app.post("/convert")
async def convert(
    background: BackgroundTasks,
    file: UploadFile = File(...),
    model_name: str = Form(..., description="Ten model trong thu muc models/ (khong can .pth)"),
    pitch: int = Form(0, description="Dich cao do (ban nhac): -12 .. +12"),
    index_rate: float = Form(0.5, description="0..1, cao hon = giong model hon"),
    f0_method: str = Form("rmvpe", description="rmvpe | harvest | pm | crepe"),
    tempo: float = Form(1.0, description="1.0 = giu nguyen; 0.85 = cham 15%"),
    vocal_gain_db: float = Form(0.0, description="Tang/giam am luong giong (dB)"),
):
    ext = Path(file.filename or "").suffix.lower()
    if ext not in ALLOWED_EXT:
        raise HTTPException(400, f"Dinh dang khong ho tro: {ext or '(trong)'}")
    if not 0.5 <= tempo <= 2.0:
        raise HTTPException(400, "tempo phai trong khoang 0.5 - 2.0")
    if not -24 <= pitch <= 24:
        raise HTTPException(400, "pitch phai trong khoang -24 .. 24")
    if f0_method not in {"rmvpe", "harvest", "pm", "crepe"}:
        raise HTTPException(400, "f0_method khong hop le")
    try:
        find_model(model_name)
    except RuntimeError as e:
        raise HTTPException(404, str(e))

    job_id = uuid.uuid4().hex
    (OUTPUT_DIR / job_id).mkdir(parents=True)
    input_path = UPLOAD_DIR / f"{job_id}{ext}"  # ten do server dat

    size = 0
    with open(input_path, "wb") as buf:
        while chunk := await file.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_UPLOAD_MB * 1024 * 1024:
                buf.close()
                input_path.unlink(missing_ok=True)
                raise HTTPException(413, f"File qua lon (toi da {MAX_UPLOAD_MB}MB)")
            buf.write(chunk)

    JOBS[job_id] = {"status": "queued", "step": "Dang cho GPU..."}
    background.add_task(
        process_job, job_id, input_path, model_name, pitch,
        index_rate, f0_method, tempo, vocal_gain_db,
    )
    return {"job_id": job_id, "status_url": f"/jobs/{job_id}"}


@app.get("/jobs/{job_id}")
def job_status(job_id: str):
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "Khong co job nay")
    return job


@app.get("/download/{job_id}")
def download(job_id: str):
    job = JOBS.get(job_id)
    if not job or job["status"] != "done":
        raise HTTPException(404, "Job chua xong hoac khong ton tai")
    path = (OUTPUT_DIR / job_id / "final_mix.mp3").resolve()
    if OUTPUT_DIR.resolve() not in path.parents or not path.is_file():
        raise HTTPException(404, "File khong ton tai")
    return FileResponse(path, media_type="audio/mpeg", filename=f"voice_converted_{job_id[:8]}.mp3")


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)