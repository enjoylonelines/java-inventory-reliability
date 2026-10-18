"""Database helper scoped to the disposable inventory-reliability Compose project."""
import subprocess

def sql(query):
    result = subprocess.run(['docker-compose', '-p', 'inventory-reliability', 'exec', '-T', 'db',
        'psql', '-X', '-v', 'ON_ERROR_STOP=1', '-U', 'demo', '-d', 'inventory_dev', '-At'],
        input=query, text=True, capture_output=True, check=True)
    return result.stdout.strip()
