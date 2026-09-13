minikube start
sudo minikube tunnel          # binds ingress-nginx to 127.0.0.1:80/443
ngrok http 80 

kubectl apply -f root/root-app.yaml