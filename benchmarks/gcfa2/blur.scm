
(letrec ((id (lambda (x) x))

         (blur (lambda (y) y))

         (lp (lambda (a n)
               (if (<= n 1)
                   (let ((res (id a)))
                        res)
                   (let* ((r ((blur id) #t))
                          (s ((blur id) #f)))
                     (not ((blur lp) s (- n 1))))))))
  (lp #f 2))
