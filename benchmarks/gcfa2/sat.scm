(define (phi x1 x2 x3 x4)
  (and (or x1 (not x2) (not x3))
       (or (not x2) (not x3))
       (or x4 x2)))

(define (try f)
  (let ((aa (f #t))
        (bb (f #f)))
    (or aa bb))
  )

(define (sat-solve-4 p)
  (try (lambda (n1)
         (try (lambda (n2)
                (try (lambda (n3)
                       (try (lambda (n4)
                              (let ((res (p n1 n2 n3 n4)))
                                    res)
                              )))))))))


(let ((a (sat-solve-4 phi))
      (b (sat-solve-4 phi)))
      a)
