#!/usr/bin/env python3
import shutil
import subprocess
import tempfile
from pathlib import Path


def main():
    for command in ("ffmpeg", "mpv"):
        if shutil.which(command) is None:
            raise SystemExit(f"Install {command} to run the native audio preference tests.")

    with tempfile.TemporaryDirectory(prefix="nuvio-audio-preferences-") as directory:
        root = Path(directory)
        media = root / "audio-preferences.mka"
        subprocess.run([
            "ffmpeg", "-v", "error", "-f", "lavfi", "-i",
            "anullsrc=r=48000:cl=stereo", "-t", "10",
            "-map", "0:a", "-map", "0:a", "-map", "0:a", "-c:a", "flac",
            "-metadata:s:a:0", "language=eng",
            "-metadata:s:a:0", "title=English dub",
            "-metadata:s:a:1", "language=jpn",
            "-metadata:s:a:1", "title=Japanese first track",
            "-metadata:s:a:2", "language=jpn",
            "-metadata:s:a:2", "title=Japanese default track",
            "-disposition:a:0", "0", "-disposition:a:1", "0",
            "-disposition:a:2", "default", str(media),
        ], check=True, timeout=30)

        script = root / "audio_preferences.lua"
        script.write_text('''
local cases = {
    {name = "startup language takes priority over container default", expected = 1},
    {name = "original prefers the same-language default track", languages = "ja", expected = 3},
    {name = "primary language takes priority", languages = "en,ja", expected = 1},
    {name = "missing primary uses secondary", languages = "fr,en", expected = 1},
    {name = "unmatched language uses engine fallback", languages = "fr", expected = 3},
    {name = "device fallback before original metadata", languages = "en", expected = 1},
    {name = "late original metadata switches language", languages = "ja", expected = 3},
    {name = "manual same-language choice stays selected", manual = 2, expected = 2},
    {name = "empty preferences restore engine default", languages = "", expected = 3},
    {name = "ISO-639-2 language preference is accepted", languages = "jpn", expected = 3},
}
local current = 0
local function next_case()
    current = current + 1
    local case = cases[current]
    if not case then
        print("All " .. #cases .. " native MPV audio tests passed")
        mp.commandv("quit", "0")
        return
    end
    if case.manual then
        mp.set_property_number("aid", case.manual)
    elseif case.languages ~= nil then
        mp.set_property("alang", case.languages)
        mp.set_property("aid", mp.get_property("aid"))
        mp.set_property("aid", "auto")
    end
    mp.add_timeout(0.2, function()
        local actual = mp.get_property_number("aid")
        if actual ~= case.expected then
            print("FAIL: " .. case.name .. "; expected=" .. case.expected .. "; actual=" .. tostring(actual))
            mp.commandv("quit", "1")
            return
        end
        print("PASS: " .. case.name)
        next_case()
    end)
end
mp.set_property("alang", "en")
mp.set_property("aid", "auto")
mp.register_event("file-loaded", next_case)
''')
        result = subprocess.run([
            "mpv", "--no-config", "--vo=null", "--ao=null", "--pause=yes",
            "--idle=yes", "--input-terminal=no", "--osc=no",
            "--msg-level=all=error,audio_preferences=info",
            f"--script={script}", str(media),
        ], capture_output=True, text=True, timeout=30)
        print(result.stdout, end="")
        if result.returncode != 0:
            print(result.stderr, end="")
            raise SystemExit(result.returncode)
        if "All 10 native MPV audio tests passed" not in result.stdout:
            raise SystemExit("MPV exited without completing the audio preference tests.")


if __name__ == "__main__":
    main()
