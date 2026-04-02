// This is a fork version of [https://github.com/waylyrics/lrc-nom](https://github.com/waylyrics/lrc-nom)

use std::time::Duration;

use chardetng::{EncodingDetector, Iso2022JpDetection, Utf8Detection};
use encoding_rs::{Encoding, UTF_16BE, UTF_16LE, UTF_8};
use nom::IResult;
use nom::{
    bytes::complete::{tag, take_until},
    multi::many1,
    sequence::tuple,
};
use thiserror::Error;

use crate::objects::Lyrics;
use crate::LyricLine;

#[derive(Debug, Error)]
pub enum LrcParseError {
    #[error("No tag was found in non-empty line {0}: {1}")]
    NoTagInNonEmptyLine(usize, String),
    #[error("Invalid timestamp format in line {0}")]
    InvalidTimestamp(usize),
    #[error("Invalid offset format in line {0}")]
    #[allow(dead_code)]
    InvalidOffset(usize),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum LyricTextDecodeSource {
    Bom,
    Utf16Heuristic,
    Utf8,
    ContentTypeCharset,
    Detector,
}

impl LyricTextDecodeSource {
    pub(crate) fn as_str(self) -> &'static str {
        match self {
            Self::Bom => "bom",
            Self::Utf16Heuristic => "utf16-heuristic",
            Self::Utf8 => "utf8",
            Self::ContentTypeCharset => "content-type",
            Self::Detector => "detector",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct DecodedLyricText {
    pub text: String,
    pub encoding_name: &'static str,
    pub source: LyricTextDecodeSource,
    pub had_errors: bool,
}

fn parse_tags(line: &str, i: usize) -> Result<(&str, Vec<(&str, &str)>), LrcParseError> {
    let res: IResult<&str, Vec<(&str, &str, &str, &str, &str)>> = many1(tuple((
        tag("["),
        take_until(":"),
        tag(":"),
        take_until("]"),
        tag("]"),
    )))(line);

    if res.is_err() {
        tracing::error!("parse_tags error {}", res.unwrap_err());
        return Err(LrcParseError::NoTagInNonEmptyLine(i, line.to_string()));
    }
    let (text, tags) = res.unwrap();
    let tags = tags
        .into_iter()
        .map(|(_left_sq, attr, _semicon, content, _right_sq)| (attr, content))
        .collect();
    Ok((text, tags))
}

pub(crate) fn decode_lyric_text(bytes: &[u8], content_type: Option<&str>) -> DecodedLyricText {
    if let Some((encoding, _bom_len)) = Encoding::for_bom(bytes) {
        let (text, had_errors) = encoding.decode_with_bom_removal(bytes);
        return DecodedLyricText {
            text: text.into_owned(),
            encoding_name: encoding.name(),
            source: LyricTextDecodeSource::Bom,
            had_errors,
        };
    }

    if let Some(encoding) = detect_utf16_without_bom(bytes) {
        let (text, had_errors) = encoding.decode_without_bom_handling(bytes);
        return DecodedLyricText {
            text: text.into_owned(),
            encoding_name: encoding.name(),
            source: LyricTextDecodeSource::Utf16Heuristic,
            had_errors,
        };
    }

    if let Ok(text) = std::str::from_utf8(bytes) {
        return DecodedLyricText {
            text: text.to_string(),
            encoding_name: UTF_8.name(),
            source: LyricTextDecodeSource::Utf8,
            had_errors: false,
        };
    }

    if let Some(encoding) = content_type
        .and_then(extract_charset_label)
        .and_then(|label| Encoding::for_label_no_replacement(label.as_bytes()))
    {
        let (text, had_errors) = encoding.decode_without_bom_handling(bytes);
        if !had_errors {
            return DecodedLyricText {
                text: text.into_owned(),
                encoding_name: encoding.name(),
                source: LyricTextDecodeSource::ContentTypeCharset,
                had_errors,
            };
        }
    }

    let mut detector = EncodingDetector::new(Iso2022JpDetection::Deny);
    detector.feed(bytes, true);
    let encoding = detector.guess(None, Utf8Detection::Allow);
    let (text, had_errors) = encoding.decode_without_bom_handling(bytes);
    DecodedLyricText {
        text: text.into_owned(),
        encoding_name: encoding.name(),
        source: LyricTextDecodeSource::Detector,
        had_errors,
    }
}

fn extract_charset_label(content_type: &str) -> Option<&str> {
    content_type.split(';').skip(1).find_map(|segment| {
        let (name, value) = segment.split_once('=')?;
        if !name.trim().eq_ignore_ascii_case("charset") {
            return None;
        }
        let value = value.trim().trim_matches('"').trim_matches('\'');
        if value.is_empty() {
            None
        } else {
            Some(value)
        }
    })
}

fn detect_utf16_without_bom(bytes: &[u8]) -> Option<&'static Encoding> {
    let sample_len = bytes.len().min(256);
    let sample_len = sample_len - (sample_len % 2);
    if sample_len < 8 {
        return None;
    }

    let sample = &bytes[..sample_len];
    let pair_count = sample.len() / 2;
    let even_nulls = sample.iter().step_by(2).filter(|&&byte| byte == 0).count();
    let odd_nulls = sample
        .iter()
        .skip(1)
        .step_by(2)
        .filter(|&&byte| byte == 0)
        .count();

    let strongly_utf16le = odd_nulls * 5 >= pair_count && even_nulls * 20 <= pair_count;
    let strongly_utf16be = even_nulls * 5 >= pair_count && odd_nulls * 20 <= pair_count;

    match (strongly_utf16le, strongly_utf16be) {
        (true, false) => Some(UTF_16LE),
        (false, true) => Some(UTF_16BE),
        _ => None,
    }
}

pub(crate) fn parse_lrc(lyric: impl Into<String>) -> Result<Lyrics, LrcParseError> {
    let lyric_lines: String = lyric.into();
    let lyric_lines = lyric_lines.trim_start_matches("\u{feff}");
    let lyric_lines: Vec<&str> = lyric_lines
        .split("\n")
        .map(|s| s.trim())
        .filter(|s| !s.is_empty())
        .collect();

    let mut lyrics = Lyrics {
        metdata: Default::default(),
        lines: Default::default(),
    };

    for (i, line) in lyric_lines.into_iter().enumerate() {
        let (text, tags) = parse_tags(line, i)?;
        match tags[0] {
            // `[:]` is considered as comment line
            ("", "") => continue,
            (attr, content) => match attr.trim() {
                "ar" => lyrics.metdata.artist = content.trim().to_string(),
                "al" => lyrics.metdata.album = content.trim().to_string(),
                "ti" => lyrics.metdata.title = content.trim().to_string(),
                "au" => lyrics.metdata.lyricist = content.trim().to_string(),
                "length" => lyrics.metdata.length = content.trim().to_string(),
                "by" => lyrics.metdata.author = content.trim().to_string(),
                "offset" => lyrics.metdata.offset = content.trim().to_string(),
                _minute if _minute.parse::<u64>().is_ok() => {
                    for (minute, second) in tags.into_iter() {
                        let minute = minute
                            .trim()
                            .parse::<u64>()
                            .map_err(|_| LrcParseError::InvalidTimestamp(i))?;
                        let second = second.replace(":", ".");
                        let second: Vec<&str> = second.split(".").collect();
                        let milliseconds = if second.len() < 2 {
                            0
                        } else {
                            let s = second[1]
                                .trim()
                                .parse::<u64>()
                                .map_err(|_| LrcParseError::InvalidTimestamp(i))?;
                            if second[1].len() == 1 {
                                s * 100
                            } else if second[1].len() == 2 {
                                s * 10
                            } else {
                                s
                            }
                        };
                        let sec = second[0]
                            .trim()
                            .parse::<u64>()
                            .map_err(|_| LrcParseError::InvalidTimestamp(i))?;

                        let minute = Duration::from_secs(minute * 60);
                        let sec = Duration::from_secs(sec);
                        let milliseconds = Duration::from_millis(milliseconds);
                        let time = minute + sec + milliseconds;

                        let text = text.trim().to_string();
                        if !text.is_empty() {
                            lyrics.lines.push(LyricLine {
                                duration: time,
                                text: text.to_string(),
                            });
                        }
                    }
                }
                _ => (), // ignores unrecognized tags
            },
        };
    }

    Ok(lyrics)
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use encoding_rs::{BIG5, EUC_JP, EUC_KR, GBK, SHIFT_JIS, WINDOWS_1252};

    use super::{
        decode_lyric_text, parse_lrc, DecodedLyricText, LyricTextDecodeSource, UTF_16BE, UTF_16LE,
    };
    use crate::LyricLine;

    #[test]
    fn lrc_1() {
        let res = parse_lrc(
            "[00:12.00]Line 1 lyrics
        [00:17.20]Line 2 lyrics

        [00:21.10][00:45.10]Repeating lyrics (e.g. chorus)",
        );
        assert!(res.is_ok());
        let res = res.unwrap();
        let mut line = res.lines.into_iter();
        assert_eq!(
            line.next(),
            Some(LyricLine {
                duration: Duration::from_secs(12),
                text: "Line 1 lyrics".to_string()
            })
        );
        assert_eq!(
            line.next(),
            Some(LyricLine {
                duration: Duration::from_secs(17) + Duration::from_millis(200),
                text: "Line 2 lyrics".to_string()
            })
        );
        assert_eq!(
            line.next(),
            Some(LyricLine {
                duration: Duration::from_secs(21) + Duration::from_millis(100),
                text: "Repeating lyrics (e.g. chorus)".to_string()
            })
        );
        assert_eq!(
            line.next(),
            Some(LyricLine {
                duration: Duration::from_secs(45) + Duration::from_millis(100),
                text: "Repeating lyrics (e.g. chorus)".to_string()
            })
        );
        assert_eq!(line.next(), None);
    }

    #[test]
    fn lrc_2() {
        let res = parse_lrc(
            "[by:Arctime]
            [00:01.00]A
            [00:02.00]B
            [00:04.00]C
            [00:02.12][00:03.67] ",
        );
        assert!(res.is_ok());
        let res = res.unwrap();
        let mut line = res.lines.into_iter();
        assert_eq!(
            line.next(),
            Some(LyricLine {
                duration: Duration::from_secs(1),
                text: "A".to_string()
            })
        );
        assert_eq!(
            line.next(),
            Some(LyricLine {
                duration: Duration::from_secs(2),
                text: "B".to_string()
            })
        );
        assert_eq!(
            line.next(),
            Some(LyricLine {
                duration: Duration::from_secs(4),
                text: "C".to_string()
            })
        );
        assert_eq!(line.next(), None);
    }

    #[test]
    fn lrc_3() {
        let res = parse_lrc("[ 00 : 01 . 00 ]A");
        assert!(res.is_ok());
        let res = res.unwrap();
        let mut line = res.lines.into_iter();
        assert_eq!(
            line.next(),
            Some(LyricLine {
                duration: Duration::from_secs(1),
                text: "A".to_string()
            })
        );
        assert_eq!(line.next(), None);
    }

    #[test]
    fn decode_utf8_bom_lrc() {
        let mut bytes = vec![0xEF, 0xBB, 0xBF];
        bytes.extend_from_slice("[00:01.00]后来\n".as_bytes());
        let decoded = decode_lyric_text(bytes.as_slice(), None);
        assert_eq!(decoded.source, LyricTextDecodeSource::Bom);
        assert_eq!(decoded.encoding_name, "UTF-8");
        assert_decoded_line(decoded, "后来");
    }

    #[test]
    fn decode_utf16le_bom_lrc() {
        let decoded = decode_lyric_text(utf16le_bytes("[00:01.00]后来\n", true).as_slice(), None);
        assert_eq!(decoded.source, LyricTextDecodeSource::Bom);
        assert_eq!(decoded.encoding_name, "UTF-16LE");
        assert_decoded_line(decoded, "后来");
    }

    #[test]
    fn decode_utf16be_bom_lrc() {
        let decoded = decode_lyric_text(utf16be_bytes("[00:01.00]後來\n", true).as_slice(), None);
        assert_eq!(decoded.source, LyricTextDecodeSource::Bom);
        assert_eq!(decoded.encoding_name, "UTF-16BE");
        assert_decoded_line(decoded, "後來");
    }

    #[test]
    fn decode_utf16le_without_bom_lrc() {
        let decoded = decode_lyric_text(utf16le_bytes("[00:01.00]后来\n", false).as_slice(), None);
        assert_eq!(decoded.source, LyricTextDecodeSource::Utf16Heuristic);
        assert_eq!(decoded.encoding_name, UTF_16LE.name());
        assert_decoded_line(decoded, "后来");
    }

    #[test]
    fn decode_utf16be_without_bom_lrc() {
        let decoded = decode_lyric_text(utf16be_bytes("[00:01.00]後來\n", false).as_slice(), None);
        assert_eq!(decoded.source, LyricTextDecodeSource::Utf16Heuristic);
        assert_eq!(decoded.encoding_name, UTF_16BE.name());
        assert_decoded_line(decoded, "後來");
    }

    #[test]
    fn decode_gbk_lrc() {
        let decoded = decode_lyric_text(
            encode_with(GBK, "[00:01.00]后来\n").as_slice(),
            Some("text/plain; charset=gb18030"),
        );
        assert_eq!(decoded.source, LyricTextDecodeSource::ContentTypeCharset);
        assert_eq!(decoded.encoding_name, "gb18030");
        assert_decoded_line(decoded, "后来");
    }

    #[test]
    fn decode_big5_lrc() {
        let decoded = decode_lyric_text(encode_with(BIG5, "[00:01.00]後來\n").as_slice(), None);
        assert_eq!(decoded.source, LyricTextDecodeSource::Detector);
        assert_eq!(decoded.encoding_name, BIG5.name());
        assert_decoded_line(decoded, "後來");
    }

    #[test]
    fn decode_shift_jis_lrc() {
        let decoded = decode_lyric_text(
            encode_with(SHIFT_JIS, "[00:01.00]さようなら\n").as_slice(),
            None,
        );
        assert_eq!(decoded.source, LyricTextDecodeSource::Detector);
        assert_eq!(decoded.encoding_name, SHIFT_JIS.name());
        assert_decoded_line(decoded, "さようなら");
    }

    #[test]
    fn decode_euc_jp_lrc() {
        let decoded = decode_lyric_text(
            encode_with(EUC_JP, "[00:01.00]こんにちは\n").as_slice(),
            None,
        );
        assert_eq!(decoded.source, LyricTextDecodeSource::Detector);
        assert_eq!(decoded.encoding_name, EUC_JP.name());
        assert_decoded_line(decoded, "こんにちは");
    }

    #[test]
    fn decode_euc_kr_lrc() {
        let decoded = decode_lyric_text(encode_with(EUC_KR, "[00:01.00]안녕\n").as_slice(), None);
        assert_eq!(decoded.source, LyricTextDecodeSource::Detector);
        assert_eq!(decoded.encoding_name, EUC_KR.name());
        assert_decoded_line(decoded, "안녕");
    }

    #[test]
    fn decode_windows_1252_lrc() {
        let decoded = decode_lyric_text(
            encode_with(WINDOWS_1252, "[00:01.00]Café déjà vu\n").as_slice(),
            None,
        );
        assert_eq!(decoded.source, LyricTextDecodeSource::Detector);
        assert_eq!(decoded.encoding_name, WINDOWS_1252.name());
        assert_decoded_line(decoded, "Café déjà vu");
    }

    fn assert_decoded_line(decoded: DecodedLyricText, expected_text: &str) {
        assert!(
            !decoded.text.contains('\u{FFFD}'),
            "decoded text should not contain replacement characters: {:?}",
            decoded.text
        );
        assert!(!decoded.had_errors, "decoding unexpectedly had errors");
        let parsed = parse_lrc(decoded.text).expect("parse decoded lrc");
        assert_eq!(parsed.lines.len(), 1);
        assert_eq!(parsed.lines[0].text, expected_text);
    }

    fn encode_with(encoding: &'static encoding_rs::Encoding, text: &str) -> Vec<u8> {
        let (encoded, _, had_errors) = encoding.encode(text);
        assert!(
            !had_errors,
            "test sample is not representable in {}",
            encoding.name()
        );
        encoded.into_owned()
    }

    fn utf16le_bytes(text: &str, with_bom: bool) -> Vec<u8> {
        utf16_bytes(text, with_bom, true)
    }

    fn utf16be_bytes(text: &str, with_bom: bool) -> Vec<u8> {
        utf16_bytes(text, with_bom, false)
    }

    fn utf16_bytes(text: &str, with_bom: bool, little_endian: bool) -> Vec<u8> {
        let mut out = Vec::new();
        if with_bom {
            if little_endian {
                out.extend_from_slice(&[0xFF, 0xFE]);
            } else {
                out.extend_from_slice(&[0xFE, 0xFF]);
            }
        }
        for unit in text.encode_utf16() {
            let bytes = if little_endian {
                unit.to_le_bytes()
            } else {
                unit.to_be_bytes()
            };
            out.extend_from_slice(bytes.as_slice());
        }
        out
    }
}
