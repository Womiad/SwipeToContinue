import argparse
import shutil
import subprocess
from pathlib import Path


DEFAULT_OUTPUT_DIR = Path("downloads") / "shorts_candidates"
DEFAULT_ARCHIVE = Path("downloads") / "downloaded_shorts.txt"
DEFAULT_FORMAT = (
    "bv*[height<=720][aspect_ratio<1][ext=mp4]+ba[ext=m4a]/"
    "b[height<=720][aspect_ratio<1][ext=mp4]/"
    "best[height<=720][aspect_ratio<1]"
)


def read_lines(path: Path) -> list[str]:
    if not path.exists():
        raise FileNotFoundError(f"File not found: {path}")
    return [
        line.strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.strip().startswith("#")
    ]


def build_targets(args: argparse.Namespace) -> list[str]:
    targets: list[str] = []

    if args.urls:
        targets.extend(read_lines(args.urls))

    queries: list[str] = []
    if args.queries:
        queries.extend(read_lines(args.queries))
    queries.extend(args.query or [])

    for query in queries:
        search_text = query if "#shorts" in query.lower() else f"#shorts {query}"
        targets.append(f"{args.search_prefix}{args.per_query}:{search_text}")

    return targets


def build_match_filter(args: argparse.Namespace) -> str:
    return f"duration <= {args.max_duration} & aspect_ratio < {args.max_aspect_ratio}"


def build_command(args: argparse.Namespace, targets: list[str]) -> list[str]:
    output_dir = args.output
    output_dir.mkdir(parents=True, exist_ok=True)
    args.archive.parent.mkdir(parents=True, exist_ok=True)

    output_template = str(
        output_dir
        / "%(upload_date>%Y%m%d)s_%(uploader).40B_%(title).80B_%(id)s.%(ext)s"
    )

    cmd = [
        "yt-dlp",
        "--ignore-errors",
        "--no-overwrites",
        "--continue",
        "--download-archive",
        str(args.archive),
        "--windows-filenames",
        "--restrict-filenames",
        "--trim-filenames",
        "170",
        "--match-filter",
        build_match_filter(args),
        "--max-filesize",
        f"{args.max_size_mb}M",
        "--format",
        args.format,
        "--merge-output-format",
        "mp4",
        "--output",
        output_template,
        "--sleep-requests",
        str(args.sleep_requests),
        "--sleep-interval",
        str(args.sleep_interval),
        "--max-sleep-interval",
        str(args.max_sleep_interval),
        "--retries",
        str(args.retries),
        "--fragment-retries",
        str(args.fragment_retries),
        "--js-runtimes",
        args.js_runtimes,
        "--extractor-args",
        f"youtube:player_client={args.youtube_player_client}",
    ]

    if args.remote_components:
        cmd.extend(["--remote-components", args.remote_components])

    if args.cookies:
        cmd.extend(["--cookies", str(args.cookies)])

    if args.cookies_from_browser:
        cmd.extend(["--cookies-from-browser", args.cookies_from_browser])

    if args.user_agent:
        cmd.extend(["--user-agent", args.user_agent])

    if args.write_metadata:
        cmd.append("--write-info-json")

    if args.write_thumbnail:
        cmd.append("--write-thumbnail")

    if args.max_total > 0:
        cmd.extend(["--max-downloads", str(args.max_total)])

    if args.simulate:
        cmd.append("--simulate")

    cmd.extend(targets)
    return cmd


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Download portrait short-video candidates with yt-dlp for later manual selection."
    )
    parser.add_argument(
        "--query",
        action="append",
        help="Search term. Can be used multiple times, for example: --query dance --query cooking",
    )
    parser.add_argument(
        "--queries",
        type=Path,
        help="Text file containing one search term per line.",
    )
    parser.add_argument(
        "--urls",
        type=Path,
        help="Text file containing one video URL per line.",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DEFAULT_OUTPUT_DIR,
        help=f"Output folder. Default: {DEFAULT_OUTPUT_DIR}",
    )
    parser.add_argument(
        "--archive",
        type=Path,
        default=DEFAULT_ARCHIVE,
        help=f"Archive file used to avoid duplicate downloads. Default: {DEFAULT_ARCHIVE}",
    )
    parser.add_argument(
        "--per-query",
        type=int,
        default=20,
        help="How many search results to request per query. Default: 20",
    )
    parser.add_argument(
        "--max-total",
        type=int,
        default=80,
        help="Maximum successful downloads for this run. Use 0 for no yt-dlp limit. Default: 80",
    )
    parser.add_argument(
        "--max-duration",
        type=int,
        default=60,
        help="Maximum duration in seconds. Default: 60",
    )
    parser.add_argument(
        "--max-aspect-ratio",
        type=float,
        default=0.99,
        help=(
            "Maximum width/height aspect ratio. Values below 1 are portrait-only. "
            "Default: 0.99"
        ),
    )
    parser.add_argument(
        "--max-size-mb",
        type=int,
        default=45,
        help="Skip files larger than this many MB. Default: 45",
    )
    parser.add_argument(
        "--search-prefix",
        default="ytsearch",
        choices=["ytsearch", "ytsearchdate"],
        help="YouTube search mode. ytsearch is relevance/popularity-ish; ytsearchdate is newer. Default: ytsearch",
    )
    parser.add_argument(
        "--format",
        default=DEFAULT_FORMAT,
        help=(
            "yt-dlp format selector. Default limits video to portrait 720p-ish MP4 "
            "when possible."
        ),
    )
    parser.add_argument(
        "--cookies-from-browser",
        choices=["brave", "chrome", "chromium", "edge", "firefox", "opera", "vivaldi", "whale"],
        help="Use cookies from a browser where YouTube is already signed in. Helps with bot checks.",
    )
    parser.add_argument(
        "--cookies",
        type=Path,
        help="Netscape-format cookies.txt file exported from a signed-in browser.",
    )
    parser.add_argument(
        "--youtube-player-client",
        default="web",
        help="YouTube player client passed to yt-dlp extractor args. Default: web",
    )
    parser.add_argument(
        "--js-runtimes",
        default="node",
        help="JavaScript runtime for yt-dlp challenge solving. Default: node",
    )
    parser.add_argument(
        "--remote-components",
        help="Allow yt-dlp remote components, for example: ejs:github",
    )
    parser.add_argument(
        "--user-agent",
        help="Optional browser user-agent override.",
    )
    parser.add_argument(
        "--write-metadata",
        action="store_true",
        help="Also save yt-dlp .info.json metadata files.",
    )
    parser.add_argument(
        "--write-thumbnail",
        action="store_true",
        help="Also save thumbnails next to downloaded videos.",
    )
    parser.add_argument("--sleep-requests", type=float, default=1.0)
    parser.add_argument("--sleep-interval", type=float, default=2.0)
    parser.add_argument("--max-sleep-interval", type=float, default=8.0)
    parser.add_argument("--retries", type=int, default=8)
    parser.add_argument("--fragment-retries", type=int, default=8)
    parser.add_argument(
        "--simulate",
        action="store_true",
        help="Show what yt-dlp would do without downloading.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()

    if shutil.which("yt-dlp") is None:
        print("yt-dlp was not found. Install it with: python -m pip install -U yt-dlp")
        return 1

    targets = build_targets(args)
    if not targets:
        print("No targets. Add --query, --queries, or --urls.")
        return 1

    print(f"Targets: {len(targets)}")
    print(f"Output: {args.output}")
    print(f"Max total downloads: {args.max_total if args.max_total > 0 else 'unlimited'}")
    print(f"Max duration: {args.max_duration}s")
    print(f"Max aspect ratio: {args.max_aspect_ratio} width/height")
    print(f"Max file size: {args.max_size_mb}MB")
    print()

    cmd = build_command(args, targets)
    return subprocess.call(cmd)


if __name__ == "__main__":
    raise SystemExit(main())
