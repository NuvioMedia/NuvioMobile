import xml.etree.ElementTree as ET
from xml.dom import minidom
import os

def load_xml(path):
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    return ET.parse(path, parser)

bn_strings_path = 'composeApp/src/commonMain/composeResources/values-bn/strings.xml'
bn_servers_path = 'composeApp/src/commonMain/composeResources/values-bn/strings_servers.xml'

# Translations dictionary (for flat strings)
translations = {
  "compose_settings_page_media_servers": "মিডিয়া সার্ভার",
  "compose_settings_page_media_server": "সার্ভার",
  "settings_content_discovery_media_servers_description": "আপনার নিজস্ব লাইব্রেরি ব্রাউজ এবং প্লে করতে Jellyfin অথবা Emby সংযুক্ত করুন।",
  "servers_section_connected": "সার্ভারসমূহ",
  "servers_section_add": "সার্ভার যোগ করুন",
  "servers_empty": "এখনো কোনো সার্ভার সংযুক্ত করা হয়নি।",
  "servers_add_description": "আপনার সার্ভার অ্যাড্রেস এবং %1$s ইউজার দিয়ে সাইন ইন করুন।",
  "servers_sign_in_title": "%1$s-এ সাইন ইন করুন",
  "servers_sign_in_subtitle": "সাইন ইন করতে আপনার সার্ভার অ্যাড্রেস এবং %1$s ইউজার লিখুন। আপনার পাসওয়ার্ড শুধুমাত্র সাইন ইন করার জন্য ব্যবহৃত হয়।",
  "servers_address": "সার্ভার অ্যাড্রেস",
  "servers_address_hint": "http://192.168.1.10:8096",
  "servers_username": "ইউজারনেম",
  "servers_password": "পাসওয়ার্ড",
  "servers_connect": "সংযুক্ত করুন",
  "servers_connecting": "সংযুক্ত হচ্ছে…",
  "servers_libraries": "লাইব্রেরিসমূহ",
  "servers_libraries_description": "নির্বাচিত লাইব্রেরিগুলো হোম সারি, সার্চ এবং স্ট্রিম ফলাফলে প্রদর্শিত হবে।",
  "servers_no_libraries": "এই ইউজারের জন্য কোনো মুভি বা সিরিজের লাইব্রেরি উপলব্ধ নেই।",
  "servers_refresh_libraries": "লাইব্রেরি রিফ্রেশ করুন",
  "servers_enabled": "চালু আছে",
  "servers_enabled_description": "এই সার্ভারটিকে হোম, সার্চ এবং স্ট্রিম ফলাফলে দেখান।",
  "servers_catalog_metadata": "অ্যাডন মেটাডেটা ব্যবহার করুন",
  "servers_catalog_metadata_description": "টাইটেলগুলো আপনার অ্যাডনের বিস্তারিত তথ্যের সাথে খুলবে এবং অন্য যেকোনো ক্যাটালগ টাইটেলের মতো সিঙ্ক হবে, তবে প্লেব্যাক এবং দেখার প্রোগ্রেস এখনো %1$s-এ পাঠানো হবে। কিছু টাইটেল নাও মিলতে পারে বা প্লে নাও হতে পারে যদি আপনার অ্যাডন সেগুলোকে ভিন্নভাবে আইডেন্টিফাই করে।",
  "servers_import_watch_state": "দেখার প্রোগ্রেস ইমপোর্ট করুন",
  "servers_import_watch_state_description": "%1$s-এ আপনি যে টাইটেলগুলো দেখছেন সেগুলো দেখার প্রোগ্রেসসহ কন্টিনিউ ওয়াচিং-এ যোগ করুন। প্লেব্যাক যেকোনো ভাবেই %1$s থেকে শুরু হবে।",
  "servers_sign_in_again": "আবার সাইন ইন করুন",
  "servers_remove": "সার্ভার সরান",
  "servers_remove_confirm_title": "এই সার্ভারটি সরাবেন?",
  "servers_remove_confirm_message": "এটি আপনাকে এই ডিভাইসে %1$s থেকে সাইন আউট করে দেবে। আপনি চাইলে পরে আবার এটি যোগ করতে পারবেন।",
  "servers_signed_in_as": "%1$s · %2$s",
  "servers_status_disabled": "বন্ধ আছে",
  "servers_status_auth": "আবার সাইন ইন করুন",
  "servers_status_unreachable": "সার্ভার আনঅ্যাভেইলেবল",
  "servers_error_auth": "ভুল ইউজারনেম বা পাসওয়ার্ড।",
  "servers_error_address": "একটি সঠিক সার্ভার অ্যাড্রেস লিখুন।",
  "servers_error_unreachable": "সার্ভারে পৌঁছানো যায়নি।",
  "servers_error_unsupported": "%1$s %2$s বা তার পরের ভার্সন প্রয়োজন।",
  "servers_error_forbidden": "এই ইউজারের অ্যাক্সেস নেই।",
  "servers_error_failed": "সার্ভারের সাথে সংযুক্ত করা যায়নি।",
  "home_empty_no_sources_title": "কোনো কন্টেন্ট সোর্স নেই",
  "home_empty_no_sources_message": "হোমে ক্যাটালগ দেখতে একটি অ্যাডন যোগ করুন বা একটি মিডিয়া সার্ভার সংযুক্ত করুন।",
  "servers_failure_auth": "সেটিংস থেকে এই সার্ভারে আবার সাইন ইন করুন।",
  "servers_failure_unreachable": "সার্ভারটি আনঅ্যাভেইলেবল।",
  "servers_failure_not_found": "এই আইটেমটি আর সার্ভারে নেই।",
  "search_empty_no_sources_message": "সার্চ করার জন্য একটি অ্যাডন যোগ করুন বা একটি মিডিয়া সার্ভার সংযুক্ত করুন।",
  "servers_playback_failed": "সার্ভার থেকে প্লেব্যাক শুরু করা যায়নি।",
  "library_server_title_unsupported": "আপনার মিডিয়া সার্ভার থেকে টাইটেলগুলো এই লাইব্রেরিতে যোগ করা যাবে না। সেগুলোকে এখানে সেভ করতে সার্ভারের জন্য 'অ্যাডন মেটাডেটা ব্যবহার করুন' চালু করুন।",
  "library_source_servers": "সার্ভার",
  "library_servers_empty_title": "কোনো লাইব্রেরি নির্বাচন করা হয়নি",
  "library_servers_empty_message": "সেটিংস-এ কন্টেন্ট ও ডিসকভারি-র অধীনে মিডিয়া সার্ভার থেকে কোন লাইব্রেরিগুলো দেখাবেন তা বাছুন।",
  "library_server_load_failed": "%1$s লোড করা যায়নি",
  "servers_audio_switch_failed": "অডিও ট্র্যাকটি পরিবর্তন করা যায়নি।",
  "servers_subtitle_switch_failed": "সাবটাইটেল ট্র্যাকটি পরিবর্তন করা যায়নি।",
  "servers_play_method_direct_play": "ডিরেক্ট প্লে",
  "servers_play_method_direct_stream": "ডিরেক্ট স্ট্রিম",
  "servers_play_method_transcode": "ট্রান্সকোডিং",
  "servers_watched_failed": "সার্ভারে দেখার প্রোগ্রেস আপডেট করা যায়নি।",
  "servers_resume_row": "দেখা চালিয়ে যান",
  "servers_failure_unsupported": "সার্ভারটি এই আইটেমটি সমর্থন করে না।",
  "servers_failure_incomplete": "এখনো এই সার্ভারের লাইব্রেরি চেক করা হচ্ছে। একটু পর আবার চেষ্টা করুন।",
  "settings_appearance_app_language_restart_title": "রিস্টার্ট প্রয়োজন",
  "settings_appearance_app_language_restart_message": "এই ভাষার লেআউট আপডেট করতে অ্যাপটি বন্ধ করে আবার খুলুন।",
  "settings_poster_always_show_landscape_clearlogo": "ল্যান্ডস্কেপ পোস্টারের জন্য সর্বদা লোগোসহ ব্যাকড্রপ ব্যবহার করুন",
  "settings_playback_preload_next_episode": "পরবর্তী পর্বের সোর্স প্রিলোড করুন",
  "settings_playback_preload_next_episode_description": "পরবর্তী পর্বের বোতাম আসার আগেই ব্যাকগ্রাউন্ডে সোর্স খোঁজা শুরু করুন।",
  "settings_playback_exo_native_memory": "ExoPlayer ন্যাটিভ মেমরি",
  "settings_playback_exo_native_memory_description": "জাভা হিপ-এর বদলে প্লেব্যাক বাফারগুলোকে ন্যাটিভ মেমরিতে রাখুন, যাতে বড় ফাইলগুলো মেমরির ওপর চাপ না ফেলেই আরও বাফার করতে পারে।",
  "settings_playback_exo_native_memory_unsupported": "এই ডিভাইসটি ন্যাটিভ প্লেব্যাক মেমরি সমর্থন করে না।",
  "settings_playback_exo_native_memory_device": "ডিভাইস মেমরি: %1$s। নিরাপদ বাফার লিমিট: %2$d MB।",
  "settings_playback_buffer_custom": "কাস্টম প্লেব্যাক বাফার",
  "settings_playback_buffer_custom_description": "প্লেয়ারের বাফার ওভাররাইড করুন। এটি বন্ধ থাকলে, ExoPlayer তার স্বাভাবিক বাফার ব্যবহার করবে, অথবা ন্যাটিভ-মেমরি চালু থাকলে তার ডিফল্ট ব্যবহার করবে।",
  "settings_playback_buffer_warning": "এই মানগুলো প্লেব্যাকের আগে ও চলাকালীন কতটা বাফার হবে তা পরিবর্তন করে। মান খুব ছোট হলে প্লেব্যাক আটকে যেতে পারে, আর খুব বড় হলে বেশি মেমরি ব্যবহার করবে।",
  "settings_playback_buffer_min": "ন্যূনতম বাফার",
  "settings_playback_buffer_max": "সর্বোচ্চ বাফার",
  "settings_playback_buffer_initial": "প্রাথমিক বাফার",
  "settings_playback_buffer_rebuffer": "আটকে যাওয়ার পর বাফার",
  "settings_playback_buffer_back": "ব্যাক বাফার",
  "settings_playback_buffer_target": "টার্গেট বাফার সাইজ",
  "settings_playback_buffer_seconds": "%1$ds",
  "settings_playback_buffer_same_as_min": "%1$ds (ন্যূনতমের সমান)",
  "settings_playback_buffer_target_warning": "নিরাপদ লিমিট %1$d MB-এর ওপরে। প্লেব্যাক মেমরির অভাবে বন্ধ হতে পারে।",
  "settings_playback_vod_cache": "VOD ডিস্ক ক্যাশে",
  "settings_playback_vod_cache_description": "বর্তমান প্রোগ্রেসিভ স্ট্রিমটি ডিস্কে সেভ করুন। মেমরি বাফার পার হলেও সিক-ব্যাক তাৎক্ষণিক হবে এবং সামান্য নেটওয়ার্ক ড্রপেও প্লেব্যাক চলতে থাকবে। HLS এবং DASH ক্যাশে হয় না।",
  "settings_playback_vod_cache_auto_size": "অটো সাইজ",
  "settings_playback_vod_cache_auto_size_description": "খালি ডিস্ক স্পেস থেকে ক্যাশের সাইজ নির্ধারণ করুন। একটি সাইজ নিজে থেকে বাছতে এটি বন্ধ করুন।",
  "settings_playback_vod_cache_size": "VOD ক্যাশে সাইজ",
  "settings_playback_vod_cache_size_value": "%1$d MB",
  "settings_playback_vod_cache_warning": "ক্যাশ করা স্ট্রিম প্লে করার সময় ডিস্কে রাইট চলতে থাকে। নতুন সাইজ অ্যাপটি পরেরবার চালু হলে প্রয়োগ হবে।",
  "settings_tracking_mdblist_section": "MDBList ফিচারসমূহ",
  "settings_mdblist_library_lists": "লাইব্রেরি তালিকা",
  "settings_mdblist_library_lists_description": "লাইব্রেরি এবং 'তালিকা ম্যানেজ করুন'-এ কোন MDBList তালিকাগুলো দেখাবে তা বাছুন। ওয়াচলিস্ট সর্বদা দেখানো হয়।",
  "settings_mdblist_library_lists_summary": "%2$dটির মধ্যে %1$dটি দেখানো হচ্ছে",
  "settings_mdblist_library_lists_empty": "আপনার MDBList অ্যাকাউন্টে এখনো কোনো তালিকা পাওয়া যায়নি। সেগুলোকে লোড করতে সিঙ্ক করুন।",
  "player_auto_skip_intro_notification": "ইন্ট্রো স্কিপ করে %1$s-এ যাওয়া হয়েছে",
  "player_auto_skip_recap_notification": "রিক্যাপ স্কিপ করে %1$s-এ যাওয়া হয়েছে",
  "player_auto_skip_outro_notification": "আউটরো স্কিপ করে %1$s-এ যাওয়া হয়েছে",
  "player_auto_skip_movie_credits_notification": "ক্রেডিট স্কিপ করে %1$s-এ যাওয়া হয়েছে",
  "youtube_resolving_stream": "YouTube ভিডিও লোড করা হচ্ছে",
  "youtube_resolution_failed": "এই YouTube ভিডিওটি লোড করা যায়নি।",
  "library_sort_released_asc": "সবচেয়ে পুরনো রিলিজ",
  "library_sort_released_desc": "সম্প্রতি রিলিজ হওয়া",
  "stream_info_server": "সার্ভার"
}

plurals = {
  "details_season_count": {
    "one": "%1$dটি সিজন",
    "other": "%1$dটি সিজন"
  }
}

# Fix existing strings according to user request
fix_map = {
  "settings_notifications_test_title": "পরীক্ষামূলক নোটিফিকেশন",
  "settings_notifications_test_send": "পরীক্ষামূলক নোটিফিকেশন পাঠান",
  "settings_notifications_test_description": "%1$s-এর জন্য একটি স্থানীয় পরীক্ষামূলক নোটিফিকেশন পাঠান।\nএই ডিভাইসে বর্তমানে %2$dটি রিলিজ অ্যালার্ট নির্ধারিত আছে।",
  "settings_notifications_test_toast_disabled": "Nuvio-এর জন্য সিস্টেম নোটিফিকেশন নিষ্ক্রিয় করা হয়েছে। অ্যালার্ট এবং পরীক্ষামূলক নোটিফিকেশন পেতে সেগুলো চালু করুন।",
  "settings_player_legacy_layout": "লেগাসি লেআউট",
  "random_episode_title": "শাফেল",
  "settings_meta_random_episode": "যেকোনো সিরিজ থেকে পর্ব শাফেল করুন। এটি বন্ধ করলে শাফেল বিরতি হয়।",
  "library_sort_added_asc": "সবচেয়ে পুরনো যোগ করা হয়েছে",
  "settings_continue_watching_show_title": "দেখা চালিয়ে যান"
}

def patch_xml(path, target_dict, is_server=False):
    if not os.path.exists(path):
        if is_server:
            # Create strings_servers.xml
            root = ET.Element("resources")
        else:
            return
    else:
        tree = load_xml(path)
        root = tree.getroot()

    # Apply fixes to existing nodes
    if not is_server:
        for elem in root.iter():
            name = elem.attrib.get('name')
            if name in fix_map:
                elem.text = fix_map[name]
                del fix_map[name]

    # Add missing elements
    for key, val in list(target_dict.items()):
        if root.find(f".//*[@name='{key}']") is None:
            el = ET.SubElement(root, "string")
            el.set("name", key)
            el.text = val
            el.tail = "\n    "
            target_dict.pop(key)

    # Plurals
    if not is_server:
        for key, pvals in plurals.items():
            if root.find(f".//*[@name='{key}']") is None:
                pel = ET.SubElement(root, "plurals")
                pel.set("name", key)
                pel.text = "\n        "
                for q, v in pvals.items():
                    item = ET.SubElement(pel, "item")
                    item.set("quantity", q)
                    item.text = v
                    item.tail = "\n        "
                item.tail = "\n    "
                pel.tail = "\n    "

    # Write out
    xmlstr = ET.tostring(root, encoding='utf-8').decode('utf-8')
    with open(path, 'w', encoding='utf-8') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n' + xmlstr)

# Split into main and server
server_keys = [k for k in translations.keys() if k.startswith('servers_') or k.startswith('compose_settings_page_media_server') or k.startswith('library_server') or k == 'settings_content_discovery_media_servers_description']
servers_dict = {k: translations[k] for k in server_keys}
main_dict = {k: translations[k] for k in translations if k not in server_keys}

patch_xml(bn_strings_path, main_dict)
patch_xml(bn_servers_path, servers_dict, is_server=True)

print("Done patching.")
