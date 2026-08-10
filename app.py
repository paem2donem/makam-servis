import os
os.environ["GRADIO_SSR_MODE"] = "false"

import uvicorn
from main import app

if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=10000)
