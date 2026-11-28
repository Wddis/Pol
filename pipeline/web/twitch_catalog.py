"""Fetch the metadata omitted from yt-dlp's flat Twitch listing."""
import json
import sys

from yt_dlp import YoutubeDL
from yt_dlp.extractor.twitch import TwitchVodIE


def video_metadata(ids):
    requests = [
        {"operationName": "VideoMetadata", "variables": {"channelLogin": "", "videoID": vod}}
        for vod in ids
    ]
    with YoutubeDL({"quiet": True, "nocheckcertificate": True, "socket_timeout": 8}) as client:
        metadata = TwitchVodIE(client)._download_gql("t2x2", requests, "Получаю даты записей")
    result = {}
    for entry in metadata:
        video = (entry.get("data") or {}).get("video")
        if video and video.get("id"):
            game = video.get("game") or {}
            result[video["id"]] = {
                "date": video.get("createdAt") or video.get("publishedAt"),
                "category": game.get("displayName") or game.get("name"),
            }
    return result


if __name__ == "__main__":
    print(json.dumps(video_metadata(sys.argv[1:]), ensure_ascii=False))
