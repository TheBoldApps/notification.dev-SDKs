import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch


spec = importlib.util.spec_from_file_location("release_github", Path(__file__).with_name("release_github.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseTests(unittest.TestCase):
    sha = "a" * 40
    checksum = "b" * 64
    draft = {"id": 1, "html_url": "https://github.com/example/release", "tag_name": "0.2.0",
             "draft": True, "target_commitish": sha}
    asset = {"name": release.ASSET, "state": "uploaded", "digest": f"sha256:{checksum}"}

    def test_manifest_extracts_version_and_checksum(self):
        manifest = f'url: "https://github.com/{release.REPO}/releases/download/0.2.0/{release.ASSET}", checksum: "{self.checksum}"'
        version, checksum = release.manifest_release(manifest)
        self.assertEqual(version, "0.2.0")
        self.assertEqual(checksum, self.checksum)
        with self.assertRaises(RuntimeError):
            release.manifest_release('checksum: "missing-release-url"')

    @patch.object(release, "request")
    def test_wrong_tag_is_never_moved(self, request):
        request.return_value = {"object": {"type": "commit", "sha": "c" * 40}}
        with self.assertRaisesRegex(RuntimeError, "will not be moved"):
            release.check_tag("token", "0.2.0", self.sha)
        self.assertEqual(request.call_count, 1)

    @patch.object(release, "request")
    def test_annotated_tag_resolves_to_commit(self, request):
        request.side_effect = [{"object": {"type": "tag", "sha": "c" * 40}},
                               {"object": {"type": "commit", "sha": self.sha}}]
        release.check_tag("token", "0.2.0", self.sha)
        self.assertEqual(request.call_count, 2)

    @patch.object(release, "request")
    def test_published_release_is_never_modified(self, request):
        request.side_effect = [{"sha": self.sha}, None, [{**self.draft, "draft": False}]]
        with self.assertRaisesRegex(RuntimeError, "already published"):
            release.publish("token", "0.2.0", self.sha, Path("unused.zip"), self.checksum)
        self.assertTrue(all(call.args[0] == "GET" for call in request.call_args_list))

    @patch.object(release, "request")
    def test_bad_draft_asset_is_not_published_or_replaced(self, request):
        request.side_effect = [{"sha": self.sha}, None, [self.draft],
                               [{**self.asset, "digest": "sha256:wrong"}]]
        with self.assertRaisesRegex(RuntimeError, "Nothing was published"):
            release.publish("token", "0.2.0", self.sha, Path("unused.zip"), self.checksum)
        self.assertTrue(all(call.args[0] == "GET" for call in request.call_args_list))

    @patch.object(release, "urlopen")
    @patch.object(release, "hashlib")
    @patch.object(release, "request")
    def test_new_release_uploads_to_draft_before_publishing(self, request, hashlib, urlopen):
        request.side_effect = [{"sha": self.sha}, None, [], self.draft, [], self.asset,
                               None, {**self.draft, "draft": False}]
        hashlib.sha256.return_value.hexdigest.return_value = self.checksum
        archive = unittest.mock.Mock()
        archive.read_bytes.return_value = b"zip bytes"
        release.publish("token", "0.2.0", self.sha, archive, self.checksum)
        calls = request.call_args_list
        self.assertTrue(calls[3].args[3]["draft"])
        self.assertEqual(calls[3].args[3]["target_commitish"], self.sha)
        self.assertEqual(calls[5].args[3], b"zip bytes")
        self.assertEqual(calls[7].args[3], {"draft": False})
        urlopen.assert_called_once()

    @patch.object(release, "request")
    def test_draft_from_other_revision_is_not_modified(self, request):
        request.side_effect = [{"sha": self.sha}, None, [{**self.draft, "target_commitish": "main"}]]
        with self.assertRaisesRegex(RuntimeError, "another revision"):
            release.publish("token", "0.2.0", self.sha, Path("unused.zip"), self.checksum)
        self.assertTrue(all(call.args[0] == "GET" for call in request.call_args_list))


if __name__ == "__main__":
    unittest.main()
