# 使用 Docker 配置代理到 ChatGPT Web 地址

## 1. 安装 Docker

如果你的系统尚未安装 Docker，请先安装。

### Linux (Ubuntu/Debian)
```sh
sudo apt update
sudo apt install -y docker.io
```

### macOS (使用 Homebrew)
```sh
brew install --cask docker
```

### Windows
从 [Docker 官方网站](https://www.docker.com/) 下载并安装 Docker Desktop。

---

## 2. 拉取 Nginx 镜像

```sh
docker pull nginx
```

---

## 3. 创建 Nginx 配置文件

新建一个目录用于存放配置文件：

```sh
mkdir -p ~/nginx_proxy
cd ~/nginx_proxy
```

创建 `nginx.conf` 配置文件：

```sh
touch nginx.conf
```

编辑 `nginx.conf` 并添加以下内容：

```nginx
worker_processes  1;

events {
    worker_connections  1024;
}

http {
    server {
        listen 80;
        server_name 10.32.52.55;  # 可以改成你的域名

        # 添加安全headers
        proxy_hide_header X-Frame-Options;
        proxy_hide_header Content-Security-Policy;

        location / {
            proxy_pass https://chat.openai.com/;
            
            # 修改和添加请求头
            proxy_set_header Host chat.openai.com;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto https;
            proxy_set_header X-Forwarded-Host $host;
            proxy_set_header Origin "https://chat.openai.com";
            proxy_set_header Referer "https://chat.openai.com";
            
            # 添加 SSL 相关配置
            proxy_ssl_server_name on;
            proxy_ssl_protocols TLSv1.2 TLSv1.3;
            proxy_ssl_verify off;
            
            # 增加超时设置
            proxy_connect_timeout 60s;
            proxy_read_timeout 60s;
            proxy_send_timeout 60s;
            
            # 替换响应中的域名
            sub_filter_once off;
            sub_filter_types *;
            sub_filter 'chat.openai.com' $host;
            sub_filter 'https://' 'http://';
            
            # 启用响应内容修改
            proxy_set_header Accept-Encoding "";
        }
    }
}
```

---

## 4. 运行 Nginx 代理容器

```sh
docker run -d --name chatgpt-proxy -p 8080:80 -v ~/nginx_proxy/nginx.conf:/etc/nginx/nginx.conf:ro nginx
```

这样，Nginx 代理就会在 `http://localhost:8080/` 监听请求，并将其转发到 `https://chat.openai.com/`。

---

## 5. 测试代理

打开浏览器，访问：

```
http://localhost:8080/
```

如果代理正常工作，你应该可以看到 ChatGPT 的 Web 界面。

---

## 6. 其他进阶设置

### 设置 HTTPS 代理（可选）
如果你希望通过 HTTPS 访问，需要使用自签证书或者 Let's Encrypt。

### 代理认证（可选）
如果你希望增加认证保护，可以在 `location /` 块中添加：

```nginx
    auth_basic "Restricted";
    auth_basic_user_file /etc/nginx/.htpasswd;
```

然后使用 `htpasswd` 生成密码文件。

### 停止并删除容器
```sh
docker stop chatgpt-proxy

docker rm chatgpt-proxy
```

这样，你就完成了使用 Docker 配置代理到 ChatGPT Web 地址的操作！

