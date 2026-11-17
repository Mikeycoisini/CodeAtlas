"""Run this file directly in PyCharm or with the project Python interpreter."""
import uvicorn


if __name__ == "__main__":
    uvicorn.run("codeatlas_ai.app:app", host="127.0.0.1", port=8001, access_log=False)
