"""Upload one part of the VM archive, read from stdin, as winvm/$FILE.

Called by vm-image.sh through `split --filter`. The part is buffered in a temporary
file that is removed automatically. With UPLOAD_DIR set, the part is copied there
instead (used to test the pipeline without Hugging Face).
"""
import os
import shutil
import sys
import tempfile

name = os.environ['FILE']
with tempfile.NamedTemporaryFile(prefix='vm-part-') as tmp:
    shutil.copyfileobj(sys.stdin.buffer, tmp, 16 << 20)
    tmp.flush()
    if os.environ.get('UPLOAD_DIR'):
        shutil.copy(tmp.name, os.path.join(os.environ['UPLOAD_DIR'], name))
    else:
        from huggingface_hub import HfApi
        HfApi(token=os.environ['HF_TOKEN']).upload_file(
            path_or_fileobj=tmp.name, path_in_repo=f'winvm/{name}',
            repo_id=os.environ['HF_VM_REPO'], repo_type='dataset')
